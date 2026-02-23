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
        eventBus.emit(Event.Message("Restarting cassandra service on all nodes."))

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            cassandraService.restart(host.toHost()).getOrThrow()
        }

        restartSidecar()
    }

    /**
     * Restart cassandra-sidecar service on Cassandra nodes
     */
    private fun restartSidecar() {
        eventBus.emit(Event.Message("Restarting cassandra-sidecar on Cassandra nodes..."))

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
            sidecarService
                .restart(host.toHost())
                .onFailure { e ->
                    eventBus.emit(Event.Error("Warning: Failed to restart cassandra-sidecar on ${host.alias}: ${e.message}"))
                }
        }

        eventBus.emit(Event.Message("cassandra-sidecar restart completed on Cassandra nodes"))
    }
}
