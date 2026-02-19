package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.output.OutputHandler

/**
 * Service for managing Grafana dashboard manifests.
 *
 * Builds Grafana K8s resources (ConfigMaps and Deployment) programmatically using
 * Fabric8 typed objects and applies them directly via the K8s API.
 */
interface GrafanaDashboardService {
    /**
     * Creates the grafana-datasources ConfigMap with runtime region.
     *
     * @param controlHost The control node running K3s
     * @param region AWS region for CloudWatch datasource
     * @return Result indicating success or failure
     */
    fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit>

    /**
     * Builds and applies all Grafana resources (dashboards, provisioning, deployment) to K8s.
     *
     * @param controlHost The control node running K3s
     * @param region AWS region for CloudWatch datasource
     * @return Result indicating success or failure
     */
    fun uploadDashboards(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit>
}

/**
 * Default implementation of GrafanaDashboardService.
 *
 * Uses [GrafanaManifestBuilder] to build typed Fabric8 K8s resources from dashboard JSON
 * resource files and applies them directly via [K8sService.applyResource].
 *
 * @property k8sService Service for K8s operations
 * @property outputHandler Handler for user-facing output
 * @property manifestBuilder Builder for Grafana K8s resources
 */
class DefaultGrafanaDashboardService(
    private val k8sService: K8sService,
    private val outputHandler: OutputHandler,
    private val manifestBuilder: GrafanaManifestBuilder,
) : GrafanaDashboardService {
    companion object {
        private const val DATASOURCES_CONFIGMAP_NAME = "grafana-datasources"
        private const val DEFAULT_NAMESPACE = "default"
    }

    override fun createDatasourcesConfigMap(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit> {
        val config = GrafanaDatasourceConfig.create(region = region)
        val yamlContent = config.toYaml()

        return k8sService.createConfigMap(
            controlHost = controlHost,
            namespace = DEFAULT_NAMESPACE,
            name = DATASOURCES_CONFIGMAP_NAME,
            data = mapOf("datasources.yaml" to yamlContent),
            labels = mapOf("app.kubernetes.io/name" to "grafana"),
        )
    }

    override fun uploadDashboards(
        controlHost: ClusterHost,
        region: String,
    ): Result<Unit> {
        outputHandler.handleMessage("Creating Grafana datasources ConfigMap...")
        createDatasourcesConfigMap(controlHost, region).getOrElse { exception ->
            return Result.failure(
                IllegalStateException("Failed to create Grafana datasources ConfigMap: ${exception.message}", exception),
            )
        }

        val resources = manifestBuilder.buildAllResources()
        outputHandler.handleMessage("Applying ${resources.size} Grafana resources...")
        for (resource in resources) {
            val kind = resource.kind
            val name = resource.metadata?.name ?: "unknown"
            outputHandler.handleMessage("Applying $kind/$name...")
            k8sService.applyResource(controlHost, resource).getOrElse { exception ->
                return Result.failure(
                    IllegalStateException("Failed to apply $kind/$name: ${exception.message}", exception),
                )
            }
        }

        outputHandler.handleMessage("All Grafana resources applied successfully!")
        return Result.success(Unit)
    }
}
