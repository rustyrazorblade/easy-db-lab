package com.rustyrazorblade.easydblab.commands.cassandra

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for async-profiler flamegraph operations on Cassandra nodes.
 *
 * Profiling runs as a long-lived systemd service on each node, uploading flamegraph
 * data to Pyroscope once per cycle (default: every 60 seconds).
 *
 * Sub-commands:
 * - start: Start continuous profiling on all Cassandra nodes
 * - stop:  Stop continuous profiling on all Cassandra nodes
 */
@Command(
    name = "profile",
    description = ["Manage async-profiler flamegraph collection on Cassandra nodes."],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProfileStart::class,
        ProfileStop::class,
    ],
)
class Profile : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
