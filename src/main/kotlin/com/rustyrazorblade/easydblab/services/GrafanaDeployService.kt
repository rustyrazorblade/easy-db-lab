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
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * Gets Grafana, its datasources and the core dashboards onto the cluster, through the K8s API only.
 *
 * The datasource ConfigMap, one ConfigMap per core dashboard ([GrafanaDashboardConfigMaps]), the
 * provisioning ConfigMap and the Deployment are Fabric8 objects applied over the K8s API; no
 * dashboard travels over SSH, on any environment. Talking to the running Grafana afterwards is
 * [GrafanaClient]'s job.
 *
 * A deploy has two steps so a caller can refuse an undeliverable dashboard before it touches the
 * cluster: [prepare] renders and checks everything and writes nothing, [apply] writes it.
 */
interface GrafanaDeployService {
    /**
     * Creates the grafana-datasources ConfigMap.
     *
     * @param tenants Every tenant in the shared store and the cluster's own; see [GrafanaDatasourceSet]
     */
    fun createDatasourcesConfigMap(tenants: TenantSet): Result<Unit>

    /**
     * Renders the dashboards with the install-time pass of [context] and builds every Grafana
     * resource, hashed. Writes nothing to the cluster, so an oversized dashboard, a ConfigMap name
     * clash or an invalid folder fails here, naming the files, before anything is applied.
     *
     * @param context The cluster the dashboards are installed on; its tenants make the datasources
     * @param bucket The account bucket, which the documents front and its signing proxy read from in its own region
     * @param hosting Where Grafana is placed and where its database lives, resolved from the environment
     * @param pyroscopeUrl The address a browser opens from the dashboards' "Pyroscope UI" link
     */
    fun prepare(
        context: DashboardInstallContext,
        bucket: String,
        hosting: ObservabilityHosting,
        pyroscopeUrl: String,
    ): Result<GrafanaRelease>

    /**
     * Creates the datasources, applies [release]'s dashboard maps and Grafana resources, then prunes
     * the dashboard ConfigMaps it does not render.
     */
    fun apply(release: GrafanaRelease): Result<Unit>

    /** [prepare], then [apply]. */
    fun deploy(
        context: DashboardInstallContext,
        bucket: String,
        hosting: ObservabilityHosting,
        pyroscopeUrl: String,
    ): Result<Unit> = prepare(context, bucket, hosting, pyroscopeUrl).mapCatching { apply(it).getOrThrow() }
}

/**
 * Everything one Grafana deploy writes, built and checked by [GrafanaDeployService.prepare].
 *
 * @property tenants The tenants the datasources are made for
 * @property resources The dashboard ConfigMaps and Grafana resources, hashed, in apply order
 * @property dashboardMaps The dashboard ConfigMap names rendered, the set the prune keeps
 */
data class GrafanaRelease(
    val tenants: TenantSet,
    val resources: List<HasMetadata>,
    val dashboardMaps: Set<String>,
)

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

    override fun prepare(
        context: DashboardInstallContext,
        bucket: String,
        hosting: ObservabilityHosting,
        pyroscopeUrl: String,
    ): Result<GrafanaRelease> {
        val (withDashboards, rendered) =
            runCatching {
                val tree = treeWriter.render(pyroscopeUrl, context)
                val grafana = manifestBuilder.buildAllResources(DocumentsBucket(bucket, bucketRegion.of(bucket)), hosting)
                val mounted = GrafanaDashboardConfigMaps.mountInto(grafana, tree)
                // Only a tree that passed validation is reported as rendered.
                eventBus.emit(Event.Grafana.DashboardsRendered(tree.size))
                mounted to GrafanaDashboardConfigMaps.namesOf(tree)
            }.getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to render Grafana dashboards: ${exception.message}", exception),
                )
            }
        // Grafana reads grafana-datasources, created by apply rather than applied with the rest, so
        // hash it in: a datasource change then rolls Grafana.
        return runCatching {
            val datasources = mapOf(DATASOURCES_CONFIGMAP_NAME to datasourcesData(context.tenants))
            GrafanaRelease(context.tenants, ConfigHashAnnotator.annotate(withDashboards, datasources), rendered)
        }
    }

    override fun apply(release: GrafanaRelease): Result<Unit> {
        eventBus.emit(Event.Grafana.DatasourcesCreating)
        createDatasourcesConfigMap(release.tenants).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        val resources = release.resources
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

        // The prune runs only after the Deployment is applied: until then the live pod template may
        // still name a map this release dropped, and deleting it would hang a new pod in
        // ContainerCreating. An apply that fails part-way returns above, before the prune, so the
        // stale maps stay (harmless, unmounted) until the next deploy that succeeds prunes them.
        k8sService.pruneConfigMaps(DEFAULT_NAMESPACE, DASHBOARD_LABELS, release.dashboardMaps).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to prune stale Grafana dashboard ConfigMaps: ${exception.message}", exception),
            )
        }

        eventBus.emit(Event.Grafana.ResourcesApplied)
        return Result.success(Unit)
    }
}
