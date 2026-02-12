package com.rustyrazorblade.easydblab.commands.dashboards

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Apply all Grafana dashboard manifests and datasource ConfigMap to K8s cluster.
 *
 * This command extracts dashboard YAML files from JAR resources, creates the
 * Grafana datasources ConfigMap with runtime configuration, and applies
 * everything to the K8s cluster.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "upload",
    description = ["Apply all dashboard manifests and datasource ConfigMap to K8s cluster"],
)
class DashboardsUpload : PicoBaseCommand() {
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
