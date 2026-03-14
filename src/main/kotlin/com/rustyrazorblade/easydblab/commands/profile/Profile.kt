package com.rustyrazorblade.easydblab.commands.profile

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for profiling operations.
 *
 * Groups subcommands for running async-profiler against cluster nodes
 * and sending the results to Pyroscope.
 *
 * Usage:
 *   easy-db-lab profile cassandra -d 30 -e cpu
 *   easy-db-lab profile cassandra -d 60 -e alloc -t
 */
@Command(
    name = "profile",
    description = ["Profile cluster nodes using async-profiler and send results to Pyroscope"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProfileCassandra::class,
    ],
)
class Profile : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
