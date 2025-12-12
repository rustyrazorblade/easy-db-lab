package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
class WriteConfig(
    val context: Context,
) : PicoCommand,
    KoinComponent {
    private val outputHandler: OutputHandler by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val clusterState by lazy { clusterStateManager.load() }

    companion object {
        private const val DEFAULT_TOKEN_COUNT = 4
        private const val DEFAULT_CONCURRENT_READS = 64
        private const val DEFAULT_CONCURRENT_WRITES = 64
    }

    @Parameters(description = ["Patch file name"], defaultValue = "cassandra.patch.yaml")
    var file: String = "cassandra.patch.yaml"

    @Option(names = ["-t", "--tokens"], description = ["Number of tokens"])
    var tokens: Int = DEFAULT_TOKEN_COUNT

    override fun execute() {
        // create the cassandra.yaml patch file
        outputHandler.handleMessage("Writing new configuration file to $file.")
        outputHandler.handleMessage(
            "It can be applied to the lab via easy-db-lab update-config " +
                "(or automatically when calling use-cassandra)",
        )

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

        context.yaml.writeValue(File("cassandra.patch.yaml"), data)
    }
}
