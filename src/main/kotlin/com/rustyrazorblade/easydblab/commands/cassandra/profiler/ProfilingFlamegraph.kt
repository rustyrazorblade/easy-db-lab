package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.profiling.JfrconvArgValidator
import com.rustyrazorblade.easydblab.profiling.requireChunkCount
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Converts recent JFR chunks into a flame graph on the node and downloads the result.
 *
 * Conversion happens on the node because `jfrconv` lives there and not on the developer's machine,
 * and because the rendered output is far smaller than the raw JFR.
 *
 * This is also the only route to Cassandra thread-pool attribution (`ReadStage-3`,
 * `CompactionExecutor:4`): Pyroscope's ingest discards thread identity entirely, so `--threads` here
 * shows something the continuous-profiling path structurally cannot.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "flamegraph",
    description = [
        "Convert recent JFR chunks into a flame graph on the node and download it.",
        "",
        "jfrconv's own arguments go after --, and are passed through untouched:",
        "  easy-db-lab cassandra profile flamegraph --last 10 -- --threads",
        "",
        "easy-db-lab supplies the input chunks and the output destination, so positional arguments",
        "and -o/--output are rejected. Use --last and --format instead.",
    ],
)
class ProfilingFlamegraph : ProfilingHostCommand() {
    private val validator = JfrconvArgValidator()

    @Option(
        names = ["--last"],
        description = ["How many of the most recent completed chunks to convert (default: \${DEFAULT-VALUE})"],
    )
    var last: Int = Constants.Profiling.DEFAULT_LAST_CHUNKS

    @Option(
        names = ["--format"],
        description = ["Output format handed to jfrconv, e.g. html or collapsed (default: \${DEFAULT-VALUE})"],
    )
    var format: String = Constants.Profiling.DEFAULT_FLAMEGRAPH_FORMAT

    @Parameters(
        description = ["jfrconv arguments, after --."],
        arity = "0..*",
    )
    var jfrconvArgs: List<String> = emptyList()

    /** Local destination root, one sub-directory per host. See [ProfilingFetch.outputDir]. */
    internal var outputDir: File = File(Constants.Profiling.LOCAL_DOWNLOAD_DIR)

    override fun execute() {
        validator.validate(jfrconvArgs)?.let { offending ->
            error(validator.rejectionMessage(offending))
        }
        requireChunkCount(last)

        forEachTarget { host ->
            val chunks = profilingService.listCompletedChunks(host, last)
            if (chunks.isEmpty()) {
                println("${host.alias}: no completed chunks yet")
                return@forEachTarget
            }

            // All chunks go into one conversion: a single rotation is rarely enough to read.
            val remotePath = profilingService.convertToFlamegraph(host, chunks, format, jfrconvArgs)
            val destination = File(outputDir, host.alias).also { it.mkdirs() }
            val local = File(destination, File(remotePath).name)
            profilingService.download(host, remotePath, local.toPath())

            eventBus.emit(
                Event.Profiling.FlamegraphCreated(
                    host = host.alias,
                    chunks = chunks.size,
                    path = local.path,
                ),
            )
        }
    }
}
