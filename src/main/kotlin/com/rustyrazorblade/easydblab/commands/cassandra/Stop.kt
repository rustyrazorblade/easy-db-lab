package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.SidecarService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

// @McpCommand - disabled for now like the original

/**
 * Stops the database on the selected db nodes (all of them by default) via its service command.
 *
 * The sidecar DaemonSet, the running-workload record and the kit stop hooks belong to the whole
 * database, so they end only once the database runs on no db node, whatever earlier stops did.
 */
@RequireProfileSetup
@RequireSSHKey
@RequiresProxy
@Command(
    name = "stop",
    description = [
        "Stop the database on the selected nodes (all nodes by default).",
        "The sidecar is removed only when every db node is stopped.",
    ],
)
class Stop : PicoBaseCommand() {
    private val cassandraService: CassandraService by inject()
    private val sidecarService: SidecarService by inject()
    private val hostOperationsService: HostOperationsService by inject()
    private val kitHookExecutor: KitHookExecutor by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        eventBus.emit(Event.Cassandra.StoppingAllNodes)

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            cassandraService.stop(host.toHost()).getOrThrow()
        }

        // The sidecar, the running workload and the kit stop hooks belong to the whole database, so
        // they end only when no db node runs it: this stop's nodes, and any a previous stop left down.
        val stopped = hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList).toSet()
        val running =
            clusterState.hosts[ServerType.Cassandra]
                .orEmpty()
                .filterNot { it in stopped }
                .filter { cassandraService.isRunning(it.toHost()).getOrThrow() }
        if (running.isEmpty()) {
            stopSidecar()
            clusterStateManager.removeRunningWorkload("cassandra")
            kitHookExecutor.firePostKitStop("cassandra")
        } else {
            eventBus.emit(Event.Cassandra.SidecarKept(running.map { it.alias }))
        }
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
