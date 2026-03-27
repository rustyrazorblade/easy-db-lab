package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
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
        eventBus.emit(Event.Cassandra.StoppingAllNodes)

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            cassandraService.stop(host.toHost()).getOrThrow()
        }

        stopSidecar()
    }

    private fun stopSidecar() {
        val controlHost = clusterState.getControlHost() ?: return
        eventBus.emit(Event.Cassandra.SidecarStopping)
        sidecarService
            .undeploy(controlHost)
            .onSuccess {
                eventBus.emit(Event.Cassandra.SidecarStopped)
            }.onFailure { e ->
                eventBus.emit(Event.Cassandra.SidecarStopFailed("${e.message}"))
            }
    }
}
