package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.commands.cassandra.stress.Stress
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for Cassandra-related operations.
 *
 * This command groups subcommands for Cassandra cluster management and tooling including:
 * - Cluster lifecycle: start, stop, restart
 * - Configuration: use, list, download-config, write-config, update-config
 * - Stress testing: stress (with nested subcommands)
 *
 * Note: The 'down' command is a top-level command (easy-db-lab down) since it tears down
 * the entire cluster infrastructure, not just Cassandra.
 */
@Command(
    name = "cassandra",
    description = ["Cassandra cluster management and tooling operations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        Cql::class,
        DownloadConfig::class,
        ListVersions::class,
        Nodetool::class,
        Restart::class,
        Start::class,
        Stop::class,
        UpdateConfig::class,
        UseCassandra::class,
        WriteConfig::class,
        Stress::class,
    ],
)
class Cassandra : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
