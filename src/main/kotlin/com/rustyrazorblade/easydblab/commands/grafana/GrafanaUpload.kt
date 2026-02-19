package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Build and apply all Grafana and Pyroscope resources to K8s cluster.
 *
 * Builds Grafana K8s resources (ConfigMaps and Deployment) from dashboard JSON resource files
 * using Fabric8, creates the datasource ConfigMap with runtime configuration, builds Pyroscope
 * resources (server and eBPF agent), and applies everything to the K8s cluster via server-side apply.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "upload",
    description = ["Build and apply all Grafana and Pyroscope resources to K8s cluster"],
)
class GrafanaUpload : PicoBaseCommand() {
    private val dashboardService: GrafanaDashboardService by inject()
    private val user: User by inject()
    private val pyroscopeManifestBuilder: PyroscopeManifestBuilder by inject()
    private val k8sService: K8sService by inject()

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()

        val region = clusterState.initConfig?.region ?: user.region

        applyPyroscopeResources(controlNode)

        dashboardService.uploadDashboards(controlNode, region).getOrElse { exception ->
            error("Failed to upload dashboards: ${exception.message}")
        }
    }

    private fun applyPyroscopeResources(controlNode: ClusterHost) {
        outputHandler.handleMessage("Preparing Pyroscope data directory...")
        remoteOps.executeRemotely(
            controlNode.toHost(),
            "sudo mkdir -p /mnt/db1/pyroscope && " +
                "sudo chown -R ${PyroscopeManifestBuilder.PYROSCOPE_UID}:${PyroscopeManifestBuilder.PYROSCOPE_UID} /mnt/db1/pyroscope",
        )

        outputHandler.handleMessage("Applying Pyroscope resources...")
        val resources = pyroscopeManifestBuilder.buildAllResources()
        for (resource in resources) {
            k8sService.applyResource(controlNode, resource).getOrElse { exception ->
                error("Failed to apply Pyroscope ${resource.kind}/${resource.metadata?.name}: ${exception.message}")
            }
        }
        outputHandler.handleMessage("Pyroscope resources applied successfully")
    }
}
