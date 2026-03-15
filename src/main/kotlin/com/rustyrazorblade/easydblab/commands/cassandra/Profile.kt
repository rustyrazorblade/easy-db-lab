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
import picocli.CommandLine.Unmatched

/**
 * Profile the database process on cluster nodes using async-profiler.
 *
 * Runs flamegraph-to-pyroscope on each node in parallel and sends the resulting
 * collapsed-stack profile to Pyroscope.
 *
 * All unrecognized options are passed directly to asprof. Common options:
 *   -d <seconds>    Profile duration (default: 30)
 *   -e <event>      Profiling event: cpu, alloc, lock, wall (default: cpu)
 *   -t              Split stack traces by thread
 *
 * Examples:
 *   cassandra profile -d 30
 *   cassandra profile -d 60 -e alloc
 *   cassandra profile -d 30 -t
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "profile",
    description = [
        "Profile Cassandra nodes using async-profiler. Results are sent to Pyroscope.",
        "Unrecognized options are passed directly to asprof.",
    ],
    mixinStandardHelpOptions = true,
)
class Profile : PicoBaseCommand() {
    private val profilingService: ProfilingService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Unmatched
    var profilerArgs: MutableList<String> = mutableListOf()

    override fun execute() {
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra]
        if (cassandraHosts.isNullOrEmpty()) {
            eventBus.emit(Event.Profiling.Error("cluster", "No Cassandra nodes found. Is the cluster running?"))
            return
        }

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, parallel = true) { host ->
            profilingService.profileNode(host.toHost(), profilerArgs)
        }
    }
}
