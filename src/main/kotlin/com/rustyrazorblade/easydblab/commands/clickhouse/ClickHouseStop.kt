package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Stop and remove the ClickHouse cluster from K8s.
 *
 * This command deletes all ClickHouse resources by label selector,
 * including Keeper nodes, server nodes, and persistent volume claims.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Stop and remove ClickHouse cluster from K8s"],
)
class ClickHouseStop : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val k8sService: K8sService by inject()

    @Option(
        names = ["--force"],
        description = ["Force deletion without confirmation"],
    )
    var force: Boolean = false

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        if (!force) {
            eventBus.emit(Event.ClickHouse.StopConfirmRequired)
            return
        }

        eventBus.emit(Event.ClickHouse.Stopping)

        // Delete ClickHouse resources by label selector
        val labelKey = "app.kubernetes.io/name"
        val labelValues = listOf("clickhouse-server", "clickhouse-keeper")

        k8sService
            .deleteResourcesByLabel(controlNode, Constants.ClickHouse.NAMESPACE, labelKey, labelValues)
            .getOrElse { exception ->
                error("Failed to delete ClickHouse cluster: ${exception.message}")
            }

        eventBus.emit(Event.ClickHouse.Stopped)
    }
}
