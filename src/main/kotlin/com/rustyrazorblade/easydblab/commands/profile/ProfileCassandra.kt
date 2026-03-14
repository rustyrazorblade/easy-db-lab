package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.ProfilingService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Unmatched

/**
 * Profile Cassandra nodes using async-profiler and send flamegraph data to Pyroscope.
 *
 * Exposes the most common async-profiler flags explicitly for discovery via --help.
 * Any additional asprof flags not listed here are passed through transparently via
 * @Unmatched — the full async-profiler flag set is supported.
 *
 * Usage:
 *   easy-db-lab profile cassandra
 *   easy-db-lab profile cassandra -d 60
 *   easy-db-lab profile cassandra -d 30 -e alloc
 *   easy-db-lab profile cassandra -d 30 -e cpu -t -i 1000000
 *   easy-db-lab profile cassandra -d 30 --title "My Profile"
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "cassandra",
    description = ["Profile Cassandra nodes with async-profiler, sending results to Pyroscope"],
    mixinStandardHelpOptions = true,
)
class ProfileCassandra : PicoBaseCommand() {
    private val profilingService: ProfilingService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["-d", "--duration"],
        description = ["Profiling duration in seconds (default: \${DEFAULT-VALUE})"],
        defaultValue = "30",
    )
    var duration: Int = 30

    @Option(
        names = ["-e", "--event"],
        description = ["Profiling event: cpu, alloc, lock, wall, ctimer, itimer (default: \${DEFAULT-VALUE})"],
        defaultValue = "cpu",
    )
    var event: String = "cpu"

    @Option(
        names = ["-t", "--threads"],
        description = ["Profile threads separately"],
    )
    var threads: Boolean = false

    @Option(
        names = ["-i", "--interval"],
        description = ["Sampling interval in nanoseconds for cpu/wall/ctimer, bytes for alloc"],
    )
    var interval: Long? = null

    @Option(
        names = ["-j", "--jstackdepth"],
        description = ["Maximum Java stack depth"],
    )
    var jstackDepth: Int? = null

    @Option(
        names = ["-I", "--include"],
        description = ["Include only stack traces containing this pattern"],
    )
    var include: String? = null

    @Option(
        names = ["-X", "--exclude"],
        description = ["Exclude stack traces containing this pattern"],
    )
    var exclude: String? = null

    @Unmatched
    var extraArgs: List<String> = emptyList()

    override fun execute() {
        val args = buildArgList()
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            profilingService.profileHost(host, args)
        }
    }

    private fun buildArgList(): List<String> {
        val args = mutableListOf<String>()
        args += listOf("-d", duration.toString())
        args += listOf("-e", event)
        if (threads) args += "-t"
        interval?.let { args += listOf("-i", it.toString()) }
        jstackDepth?.let { args += listOf("-j", it.toString()) }
        include?.let { args += listOf("-I", it) }
        exclude?.let { args += listOf("-X", it) }
        args += extraArgs
        return args
    }
}
