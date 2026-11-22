package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Write a new cassandra configuration patch file.
 */
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "write-config",
    aliases = ["wc"],
    description = ["Write a new cassandra configuration patch file"],
)
class WriteConfig : PicoBaseCommand() {
    companion object {
        private const val DEFAULT_TOKEN_COUNT = 4
        private const val DEFAULT_CONCURRENT_READS = 64
        private const val DEFAULT_CONCURRENT_WRITES = 64
    }

    @Parameters(
        arity = "0..1",
        description = ["Patch file to write (default: the workspace's ${Constants.ConfigPaths.CASSANDRA_PATCH_FILE})"],
    )
    var file: String? = null

    @Option(names = ["-t", "--tokens"], description = ["Number of tokens"])
    var tokens: Int = DEFAULT_TOKEN_COUNT

    override fun execute() {
        // A typed path is the user's, as typed; with none, the workspace's patch file.
        val patchFile = file?.let(::File) ?: context.workspaceFile(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)
        eventBus.emit(Event.Cassandra.WriteConfigDone(patchFile.path))
        eventBus.emit(Event.Cassandra.WriteConfigHint)

        val data =
            object {
                val cluster_name = clusterState.name
                val num_tokens = tokens
                val seed_provider =
                    object {
                        val class_name = "org.apache.cassandra.locator.SimpleSeedProvider"
                        val parameters =
                            object {
                                val seeds =
                                    clusterState
                                        .getHosts(ServerType.Cassandra)
                                        .map { it.private }
                                        .take(1)
                                        .joinToString(",")
                            }
                    }
                val hints_directory = "/mnt/db1/cassandra/hints"
                val data_file_directories = listOf("/mnt/db1/cassandra/data")
                val commitlog_directory = "/mnt/db1/cassandra/commitlog"
                val concurrent_reads = DEFAULT_CONCURRENT_READS
                val concurrent_writes = DEFAULT_CONCURRENT_WRITES
                val trickle_fsync = true
                val endpoint_snitch = "Ec2Snitch"
            }

        context.yaml.writeValue(patchFile, data)
    }
}
