package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
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

// @McpCommand - disabled for now like the original

/**
 * Stop cassandra on all nodes via service command.
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "stop",
    description = ["Stop cassandra on all nodes via service command"],
)
class Stop : PicoBaseCommand() {
    private val cassandraService: CassandraService by inject()
    private val sidecarService: SidecarService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        eventBus.emit(Event.Message("Stopping cassandra service on all nodes."))

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            cassandraService.stop(host.toHost()).getOrThrow()
        }

        stopSidecar()
    }

    /**
     * Stop cassandra-sidecar service on Cassandra nodes
     */
    private fun stopSidecar() {
        eventBus.emit(Event.Message("Stopping cassandra-sidecar on Cassandra nodes..."))

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
            sidecarService
                .stop(host.toHost())
                .onFailure { e ->
                    eventBus.emit(Event.Error("Warning: Failed to stop cassandra-sidecar on ${host.alias}: ${e.message}"))
                }
        }

        eventBus.emit(Event.Message("cassandra-sidecar shutdown completed on Cassandra nodes"))
    }
}
