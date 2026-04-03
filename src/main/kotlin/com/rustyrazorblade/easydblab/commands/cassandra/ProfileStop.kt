package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.ProfilingService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Stop continuous async-profiler flamegraph collection on all Cassandra nodes.
 *
 * Stops the `flamegraph-cassandra` systemd service on each node in parallel.
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "stop",
    description = ["Stop continuous profiling on all Cassandra nodes."],
    mixinStandardHelpOptions = true,
)
class ProfileStop : PicoBaseCommand() {
    private val profilingService: ProfilingService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    override fun execute() {
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra]
        if (cassandraHosts.isNullOrEmpty()) {
            eventBus.emit(Event.Profiling.Error("cluster", "No Cassandra nodes found. Is the cluster running?"))
            return
        }

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, parallel = true) { host ->
            profilingService.stop(host.toHost())
        }
    }
}
