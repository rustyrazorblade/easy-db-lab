package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.BucketRegion

/**
 * Gets Grafana, its datasources and the core dashboard tree onto the cluster.
 *
 * Core dashboards travel as a file tree copied to the control node ([GrafanaDashboardTreeUploader]);
 * the datasource ConfigMap, the provisioning ConfigMap and the Deployment are Fabric8 objects applied
 * over the K8s API. Talking to the running Grafana afterwards is [GrafanaClient]'s job.
 */
interface GrafanaDeployService {
    /**
     * Creates the grafana-datasources ConfigMap.
     *
     * @param controlHost The control node running K3s
     * @param tenants Every tenant in the shared store and the cluster's own; see [GrafanaDatasourceSet]
     */
    fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        tenants: TenantSet,
    ): Result<Unit>

    /**
     * Creates the datasources, puts the dashboard tree on the control node with the install-time
     * pass of [context] applied, then builds and applies the Grafana K8s resources.
     *
     * @param controlHost The control node running K3s
     * @param context The cluster the dashboards are installed on; its tenants make the datasources
     * @param bucket The account bucket, which the documents sidecars read from in its own region
     */
    fun deploy(
        controlHost: ClusterHost,
        context: DashboardInstallContext,
        bucket: String,
    ): Result<Unit>
}

/**
 * Default [GrafanaDeployService]: [K8sService] applies the resources [GrafanaManifestBuilder]
 * builds, each workload hashed with [ConfigHashAnnotator] so a datasource change rolls Grafana.
 *
 * @property k8sService Service for K8s operations
 * @property manifestBuilder Builder for Grafana K8s resources
 * @property treeUploader Copies the dashboard tree onto the Grafana hostPath
 * @property configChangeReport Says whether Grafana rolls
 * @property bucketRegion Finds the account bucket's region, which the documents proxy signs for
 */
class DefaultGrafanaDeployService(
    private val k8sService: K8sService,
    private val manifestBuilder: GrafanaManifestBuilder,
    private val treeUploader: GrafanaDashboardTreeUploader,
    private val eventBus: EventBus,
    private val configChangeReport: ConfigChangeReport,
    private val bucketRegion: BucketRegion,
) : GrafanaDeployService {
    companion object {
        private const val DATASOURCES_CONFIGMAP_NAME = "grafana-datasources"
        private const val DEFAULT_NAMESPACE = "default"
    }

    override fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        tenants: TenantSet,
    ): Result<Unit> =
        k8sService.createConfigMap(
            controlHost = controlHost,
            namespace = DEFAULT_NAMESPACE,
            name = DATASOURCES_CONFIGMAP_NAME,
            data = datasourcesData(tenants),
            labels = mapOf("app.kubernetes.io/name" to "grafana"),
        )

    /** The contents of the `grafana-datasources` ConfigMap for [tenants]. */
    private fun datasourcesData(tenants: TenantSet): Map<String, String> =
        mapOf("datasources.yaml" to GrafanaDatasourceSet.build(tenants, BackendUrls.CONTROL_NODE).toYaml())

    override fun deploy(
        controlHost: ClusterHost,
        context: DashboardInstallContext,
        bucket: String,
    ): Result<Unit> {
        eventBus.emit(Event.Grafana.DatasourcesCreating)
        createDatasourcesConfigMap(controlHost, context.tenants).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        runCatching { treeUploader.upload(controlHost, context) }.getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to upload Grafana dashboards: ${exception.message}", exception),
            )
        }

        // Grafana reads grafana-datasources, created above rather than applied with the rest, so hash
        // it in: a datasource change then rolls Grafana.
        val resources =
            ConfigHashAnnotator.annotate(
                manifestBuilder.buildAllResources(DocumentsBucket(bucket, bucketRegion.of(bucket))),
                mapOf(DATASOURCES_CONFIGMAP_NAME to datasourcesData(context.tenants)),
            )
        runCatching { configChangeReport.report(controlHost, resources, DEFAULT_NAMESPACE) }
            .getOrElse { exception -> return Result.failure(exception) }
        eventBus.emit(Event.Grafana.ResourcesApplying(resources.size))
        for (resource in resources) {
            val kind = resource.kind
            val name = resource.metadata?.name ?: "unknown"
            eventBus.emit(Event.Grafana.ResourceApplying(kind, name))
            k8sService.applyResource(controlHost, resource).getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to apply $kind/$name: ${exception.message}", exception),
                )
            }
        }

        eventBus.emit(Event.Grafana.ResourcesApplied)
        return Result.success(Unit)
    }
}
