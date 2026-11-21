package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardConfigMaps
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.BucketRegion

/**
 * Gets Grafana, its datasources and the core dashboards onto the cluster, through the K8s API only.
 *
 * The datasource ConfigMap, one ConfigMap per core dashboard ([GrafanaDashboardConfigMaps]), the
 * provisioning ConfigMap and the Deployment are Fabric8 objects applied over the K8s API; no
 * dashboard travels over SSH, on any environment. Talking to the running Grafana afterwards is
 * [GrafanaClient]'s job.
 */
interface GrafanaDeployService {
    /**
     * Creates the grafana-datasources ConfigMap.
     *
     * @param tenants Every tenant in the shared store and the cluster's own; see [GrafanaDatasourceSet]
     */
    fun createDatasourcesConfigMap(tenants: TenantSet): Result<Unit>

    /**
     * Creates the datasources, renders the dashboards with the install-time pass of [context],
     * applies them with the Grafana K8s resources, then prunes the dashboard ConfigMaps no longer
     * rendered.
     *
     * @param context The cluster the dashboards are installed on; its tenants make the datasources
     * @param bucket The account bucket, which the documents front and its signing proxy read from in its own region
     * @param hosting Where Grafana is placed and where its database lives, resolved from the environment
     * @param pyroscopeUrl The address a browser opens from the dashboards' "Pyroscope UI" link
     */
    fun deploy(
        context: DashboardInstallContext,
        bucket: String,
        hosting: ObservabilityHosting,
        pyroscopeUrl: String,
    ): Result<Unit>
}

/**
 * Default [GrafanaDeployService]: [K8sService] applies the resources [GrafanaManifestBuilder]
 * builds, with the dashboard ConfigMaps mounted in, each workload hashed with [ConfigHashAnnotator]
 * so a datasource or dashboard change rolls Grafana.
 *
 * The order is load-bearing: the dashboard maps are applied, then the Deployment whose template no
 * longer names any removed map, and only then are the stale maps pruned. Pruning first would leave
 * a live template naming a deleted map, and a new pod would hang in ContainerCreating.
 *
 * @property k8sService Service for K8s operations
 * @property manifestBuilder Builder for Grafana K8s resources
 * @property treeWriter Renders the core dashboards from the classpath
 * @property configChangeReport Says whether Grafana rolls
 * @property bucketRegion Finds the account bucket's region, which the documents proxy signs for
 */
class DefaultGrafanaDeployService(
    private val k8sService: K8sService,
    private val manifestBuilder: GrafanaManifestBuilder,
    private val treeWriter: GrafanaDashboardTreeWriter,
    private val eventBus: EventBus,
    private val configChangeReport: ConfigChangeReport,
    private val bucketRegion: BucketRegion,
) : GrafanaDeployService {
    companion object {
        private const val DATASOURCES_CONFIGMAP_NAME = "grafana-datasources"
        private const val DEFAULT_NAMESPACE = "default"
        private val DASHBOARD_LABELS =
            mapOf(Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL to Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE)
    }

    override fun createDatasourcesConfigMap(tenants: TenantSet): Result<Unit> =
        k8sService.createConfigMap(
            namespace = DEFAULT_NAMESPACE,
            name = DATASOURCES_CONFIGMAP_NAME,
            data = datasourcesData(tenants),
            labels = mapOf("app.kubernetes.io/name" to "grafana"),
        )

    /** The contents of the `grafana-datasources` ConfigMap for [tenants]. */
    private fun datasourcesData(tenants: TenantSet): Map<String, String> =
        mapOf("datasources.yaml" to GrafanaDatasourceSet.build(tenants, BackendUrls.IN_CLUSTER).toYaml())

    override fun deploy(
        context: DashboardInstallContext,
        bucket: String,
        hosting: ObservabilityHosting,
        pyroscopeUrl: String,
    ): Result<Unit> {
        eventBus.emit(Event.Grafana.DatasourcesCreating)
        createDatasourcesConfigMap(context.tenants).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        val grafana = manifestBuilder.buildAllResources(DocumentsBucket(bucket, bucketRegion.of(bucket)), hosting)
        val (withDashboards, rendered) =
            runCatching {
                val tree = treeWriter.render(pyroscopeUrl, context)
                eventBus.emit(Event.Grafana.DashboardsRendered(tree.size))
                GrafanaDashboardConfigMaps.mountInto(grafana, tree) to GrafanaDashboardConfigMaps.namesOf(tree)
            }.getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to render Grafana dashboards: ${exception.message}", exception),
                )
            }

        // Grafana reads grafana-datasources, created above rather than applied with the rest, so hash
        // it in: a datasource change then rolls Grafana.
        val resources =
            ConfigHashAnnotator.annotate(withDashboards, mapOf(DATASOURCES_CONFIGMAP_NAME to datasourcesData(context.tenants)))
        runCatching { configChangeReport.report(resources, DEFAULT_NAMESPACE) }
            .getOrElse { exception -> return Result.failure(exception) }
        eventBus.emit(Event.Grafana.ResourcesApplying(resources.size))
        for (resource in resources) {
            val kind = resource.kind
            val name = resource.metadata?.name ?: "unknown"
            eventBus.emit(Event.Grafana.ResourceApplying(kind, name))
            k8sService.applyResource(resource).getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to apply $kind/$name: ${exception.message}", exception),
                )
            }
        }

        k8sService.pruneConfigMaps(DEFAULT_NAMESPACE, DASHBOARD_LABELS, rendered).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to prune stale Grafana dashboard ConfigMaps: ${exception.message}", exception),
            )
        }

        eventBus.emit(Event.Grafana.ResourcesApplied)
        return Result.success(Unit)
    }
}
