package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

@RequireProfileSetup
@Command(
    name = "install",
    description = ["Upload a Grafana dashboard JSON file to the running Grafana instance"],
    mixinStandardHelpOptions = true,
)
class GrafanaInstall : PicoBaseCommand() {
    @Parameters(index = "0", description = ["Path to the dashboard JSON file"])
    lateinit var dashboardPath: String

    @Option(names = ["--folder"], description = ["Grafana folder name (created if it doesn't exist)"], defaultValue = "General")
    var folderName: String = "General"

    private val grafanaDashboardService: GrafanaDashboardService by inject()

    override fun execute() {
        val file = File(dashboardPath)
        require(file.exists()) { "Dashboard file not found: $dashboardPath" }

        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")

        grafanaDashboardService
            .installDashboardFromFile(file = file, controlHost = controlHost, folderName = folderName)
            .getOrThrow()
    }
}
