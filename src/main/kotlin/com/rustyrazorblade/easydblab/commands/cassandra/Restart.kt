package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
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

    /**
     * Rolling restart of the cassandra-sidecar DaemonSet
     */
    private fun restartSidecar() {
        eventBus.emit(Event.Cassandra.SidecarRestarting)

        val controlHost = clusterState.hosts[ServerType.Control]?.firstOrNull()
        if (controlHost != null) {
            sidecarService
                .rolloutRestart(controlHost)
                .onFailure { e ->
                    eventBus.emit(Event.Cassandra.SidecarRestartFailed("all", "${e.message}"))
                }
        }

        eventBus.emit(Event.Cassandra.SidecarRestarted)
    }
}
