package com.rustyrazorblade.easydblab.commands.profile

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for profiling cluster nodes using async-profiler.
 *
 * Subcommands profile specific database processes and send flamegraph data to Pyroscope.
 * All subcommands pass extra options directly to asprof (the async-profiler binary).
 *
 * Available subcommands:
 * - cassandra: Profile the Cassandra process
 */
@Command(
    name = "profile",
    description = ["Profile cluster nodes using async-profiler. Results are sent to Pyroscope."],
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
