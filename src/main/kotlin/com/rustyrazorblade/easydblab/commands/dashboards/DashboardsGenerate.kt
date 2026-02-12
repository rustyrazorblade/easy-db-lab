package com.rustyrazorblade.easydblab.commands.dashboards

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Extract all Grafana dashboard manifests from JAR resources to local k8s/ directory.
 *
 * This command re-extracts dashboard YAML files (both core and ClickHouse) from JAR
 * resources, enabling rapid dashboard iteration without re-running init.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "generate",
    description = ["Extract all Grafana dashboard manifests from JAR resources"],
)
class DashboardsGenerate : PicoBaseCommand() {
    private val dashboardService: GrafanaDashboardService by inject()

    override fun execute() {
        outputHandler.handleMessage("Extracting Grafana dashboard manifests...")
        val dashboardFiles = dashboardService.extractDashboardResources()

        if (dashboardFiles.isEmpty()) {
            error("No dashboard resources found")
        }

        outputHandler.handleMessage("Extracted ${dashboardFiles.size} dashboard file(s):")
        dashboardFiles.forEach { file ->
            outputHandler.handleMessage("  ${file.path}")
        }
    }
}
