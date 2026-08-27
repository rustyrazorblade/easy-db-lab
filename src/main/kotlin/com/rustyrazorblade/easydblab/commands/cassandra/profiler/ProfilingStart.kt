package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.profiling.AsprofArgValidator
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.profiling.requireProfilingBounds
import com.rustyrazorblade.easydblab.profiling.requireProfilingLoopInterval
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.time.Instant

/**
 * Enables profiling on the targeted Cassandra nodes with a given set of async-profiler arguments.
 *
 * The command records desired state; the node's reconciler attaches within one interval. Changing
 * the arguments replaces the running session — the outgoing one is stopped cleanly, so its in-flight
 * chunk is finalized and still ships.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "start",
    description = [
        "Enable continuous profiling on Cassandra nodes.",
        "",
        "async-profiler's own arguments go after --, and are passed through untouched:",
        "  easy-db-lab cassandra profile start -- -e wall -i 10ms",
        "",
        "easy-db-lab supplies the output file, output format, rotation, and session duration, so",
        "-f/--file, -o/--output, --loop, -d/--duration and --timeout are rejected. Use this",
        "command's own --loop to change the rotation interval.",
        "",
        "--nobatch is rejected too, on the same ground: it makes async-profiler emit wall-clock",
        "samples as jdk.ExecutionSample, so the shipper cannot tell them from CPU samples.",
        "",
        "WARNING: never combine a CPU event with wall-clock sampling in one recording (for example",
        "'-e cpu --wall 10ms'). Pyroscope's JFR parser carries a defect that lets the wall event's",
        "batch count overwrite every subsequent CPU sample's weight, silently corrupting the CPU",
        "profile by up to three orders of magnitude. Run one mode at a time and switch with",
        "stop/start; separate sessions are unaffected.",
    ],
)
class ProfilingStart : ProfilingHostCommand() {
    private val validator = AsprofArgValidator()

    @Option(
        names = ["--loop"],
        description = [
            "JFR rotation interval (default: \${DEFAULT-VALUE}). A whole number of seconds (30)",
            "or a number with a unit (30s, 5m, 1h).",
        ],
    )
    var loopInterval: String = Constants.Profiling.DEFAULT_LOOP_INTERVAL

    @Option(
        names = ["--retention"],
        description = ["Minutes of profile data to keep on each node (default: \${DEFAULT-VALUE})"],
    )
    var retentionMinutes: Int = Constants.Profiling.DEFAULT_RETENTION_MINUTES

    @Option(
        names = ["--max-bytes"],
        description = ["Byte ceiling for each node's profile directory (default: \${DEFAULT-VALUE})"],
    )
    var maxBytes: Long = Constants.Profiling.DEFAULT_MAX_BYTES

    @Parameters(
        description = ["async-profiler arguments, after --. Defaults to '-e cpu'."],
        arity = "0..*",
    )
    var asprofArgs: List<String> = Constants.Profiling.DEFAULT_ASPROF_ARGS

    override fun execute() {
        // Fail here, not after an SSH round-trip: a rejected argument is a mistake in the request.
        validator.validate(asprofArgs)?.let { offending ->
            error(validator.rejectionMessage(offending))
        }
        // The rotation interval is checked first because the bounds check below is measured against
        // it: the node cannot ship a chunk it has already pruned.
        requireProfilingLoopInterval(loopInterval)
        // Same reason as the argument validator, and more urgently: an out-of-range bound fails on
        // the node *silently*, with the CLI having already reported profiling as enabled on every
        // one of them.
        requireProfilingBounds(
            retentionMinutes = retentionMinutes,
            maxBytes = maxBytes,
            loopInterval = loopInterval,
        )

        val config =
            ProfilingConfig(
                enabled = true,
                asprofArgs = asprofArgs,
                loopInterval = loopInterval,
                retentionMinutes = retentionMinutes,
                maxBytes = maxBytes,
                pyroscopeUrl = pyroscopeIngestBaseUrl(controlNodePrivateIp()),
                clusterName = clusterState.clusterLabelName(),
                updatedAt = Instant.now().toString(),
            )

        forEachTarget { host ->
            profilingService.writeDesiredState(host, config)
            eventBus.emit(
                Event.Profiling.Started(
                    host = host.alias,
                    userArgs = asprofArgs,
                    loopInterval = loopInterval,
                ),
            )
        }
    }
}
