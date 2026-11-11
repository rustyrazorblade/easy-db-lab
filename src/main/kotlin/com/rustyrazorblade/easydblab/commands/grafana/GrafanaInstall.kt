package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardDefaults
import com.rustyrazorblade.easydblab.services.DashboardInstallContextFactory
import com.rustyrazorblade.easydblab.services.GrafanaClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Uploads one dashboard JSON file to the running Grafana, after the same install-time pass `up`
 * and kit `start` apply, so what the `dashboard-editor` agent reads back is what `up` installs.
 */
@RequireProfileSetup
@RequiresProxy
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

    private val grafanaClient: GrafanaClient by inject()
    private val installContextFactory: DashboardInstallContextFactory by inject()

    override fun execute() {
        val file = File(dashboardPath)
        require(file.exists()) { "Dashboard file not found: $dashboardPath" }

        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")

        val dashboard =
            DashboardDefaults.apply(
                Json.parseToJsonElement(file.readText()).jsonObject,
                installContextFactory.forCluster(clusterState, controlHost),
            )
        grafanaClient
            .installDashboard(dashboard = dashboard, controlHost = controlHost, folderName = folderName)
            .getOrThrow()
    }
}
