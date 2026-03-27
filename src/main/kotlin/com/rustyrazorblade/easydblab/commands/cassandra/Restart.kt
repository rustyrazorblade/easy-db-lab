package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getControlHost
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.SidecarService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Restart cassandra on all nodes.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "restart",
    description = ["Restart cassandra"],
)
class Restart : PicoBaseCommand() {
    private val cassandraService: CassandraService by inject()
    private val sidecarService: SidecarService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        eventBus.emit(Event.Cassandra.RestartingAllNodes)

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            cassandraService.restart(host.toHost()).getOrThrow()
        }

        restartSidecar()
    }

    private fun restartSidecar() {
        val controlHost = clusterState.getControlHost() ?: return
        eventBus.emit(Event.Cassandra.SidecarRestarting)
        sidecarService
            .restart(controlHost)
            .onSuccess {
                eventBus.emit(Event.Cassandra.SidecarRestarted)
            }.onFailure { e ->
                eventBus.emit(Event.Cassandra.SidecarRestartFailed("${e.message}"))
            }
    }
}
