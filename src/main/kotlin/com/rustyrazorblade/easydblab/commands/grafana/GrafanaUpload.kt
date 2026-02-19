package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Build and apply all Grafana dashboard manifests and datasource ConfigMap to K8s cluster.
 *
 * Builds Grafana K8s resources (ConfigMaps and Deployment) from dashboard JSON resource files
 * using Fabric8, creates the datasource ConfigMap with runtime configuration, and applies
 * everything to the K8s cluster via server-side apply.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "upload",
    description = ["Build and apply all Grafana resources to K8s cluster"],
)
class GrafanaUpload : PicoBaseCommand() {
    private val dashboardService: GrafanaDashboardService by inject()
    private val user: User by inject()

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()

        val region = clusterState.initConfig?.region ?: user.region

        dashboardService.uploadDashboards(controlNode, region).getOrElse { exception ->
            error("Failed to upload dashboards: ${exception.message}")
        }
    }
}
