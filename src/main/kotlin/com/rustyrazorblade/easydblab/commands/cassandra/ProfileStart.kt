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
import picocli.CommandLine.Option
import picocli.CommandLine.Unmatched

/**
 * Start continuous async-profiler flamegraph collection on all Cassandra nodes.
 *
 * Starts the `flamegraph-cassandra` systemd service on each node. The service loops
 * indefinitely: profile for --loop-interval seconds (default: 60), upload to
 * Pyroscope, repeat — until stopped with `cassandra profile stop`.
 *
 * Unrecognized options are passed directly to asprof. Common options:
 *   -e <event>          Profiling event: cpu, alloc, lock, wall, nativemem (default: cpu)
 *   -e cpu,alloc,lock   Multiple events (JFR format required, used automatically)
 *   --alloc <size>      Allocation sampling interval (e.g. 500k)
 *   --lock <duration>   Lock profiling threshold (e.g. 10ms)
 *   --nativemem <size>  Native memory leak profiling
 *   --all               Enable cpu, wall, alloc, live, lock, nativemem simultaneously
 *   -i <interval>       Sampling interval (e.g. 50ms)
 *   -t                  Split stack traces by thread
 *
 * Examples:
 *   cassandra profile start
 *   cassandra profile start -e alloc
 *   cassandra profile start --loop-interval 30 -e cpu,alloc,lock
 *   cassandra profile start --all --alloc 2m
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "start",
    description = [
        "Start continuous profiling on all Cassandra nodes.",
        "Data is uploaded to Pyroscope every --loop-interval seconds (default: 60).",
        "Unrecognized options are passed directly to asprof.",
    ],
    mixinStandardHelpOptions = true,
)
class ProfileStart : PicoBaseCommand() {
    private val profilingService: ProfilingService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Option(
        names = ["--loop-interval"],
        description = ["Loop duration in seconds: profile this long, upload, repeat (default: 60)."],
    )
    var loopInterval: Int? = null

    @Unmatched
    var profilerArgs: MutableList<String> = mutableListOf()

    override fun execute() {
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra]
        if (cassandraHosts.isNullOrEmpty()) {
            eventBus.emit(Event.Profiling.Error("cluster", "No Cassandra nodes found. Is the cluster running?"))
            return
        }

        // Best-effort: failures on individual nodes emit Event.Profiling.Error
        // but don't abort profiling on remaining nodes.
        val args = profilerArgs.toList()
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, parallel = true) { host ->
            profilingService.startProfiling(host.toHost(), args, loopInterval)
        }
    }
}
