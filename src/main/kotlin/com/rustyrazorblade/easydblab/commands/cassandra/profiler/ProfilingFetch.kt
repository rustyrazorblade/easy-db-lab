package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.profiling.requireChunkCount
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File

/**
 * Downloads raw JFR chunks from the targeted Cassandra nodes into the local workspace.
 *
 * Only completed chunks are offered — the one currently being written is excluded, because an
 * unfinalized chunk has no constant pool and no tool will read it.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "fetch",
    description = ["Download completed JFR chunks from Cassandra nodes"],
)
class ProfilingFetch : ProfilingHostCommand() {
    @Option(
        names = ["--last"],
        description = ["How many of the most recent completed chunks to download (default: \${DEFAULT-VALUE})"],
    )
    var last: Int = Constants.Profiling.DEFAULT_LAST_CHUNKS

    /**
     * Local destination root, one sub-directory per host. Not a CLI option: the workspace layout is
     * fixed, and this exists so tests can point it somewhere temporary.
     */
    internal var outputDir: File = File(Constants.Profiling.LOCAL_DOWNLOAD_DIR)

    override fun execute() {
        requireChunkCount(last)
        forEachTarget { host ->
            val chunks = profilingService.listCompletedChunks(host, last)
            if (chunks.isEmpty()) {
                println("${host.alias}: no completed chunks yet")
                return@forEachTarget
            }
            val destination = File(outputDir, host.alias)
            profilingService.fetchChunks(host, chunks, destination)
            eventBus.emit(
                Event.Profiling.ChunksFetched(
                    host = host.alias,
                    chunks = chunks.size,
                    destination = destination.path,
                ),
            )
        }
    }
}
