package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for runtime-controlled continuous profiling of Cassandra nodes.
 *
 * Profiling is driven by async-profiler attached to the already-running JVM, so what is being
 * profiled changes without restarting the database — which on a benchmarking rig matters, because a
 * restart discards exactly the page cache and compaction state the operator is trying to observe.
 *
 * Sub-commands:
 * - start: enable profiling with a given set of async-profiler arguments
 * - stop: disable profiling
 * - status: report what each node is actually doing
 * - fetch: download raw JFR chunks
 * - flamegraph: convert recent chunks into a flame graph on the node and download it
 *
 * `profiler` stays registered as an alias: the group named the tool it drives before it named the
 * action, and operator muscle memory and existing scripts still spell it that way.
 */
@Command(
    name = "profile",
    aliases = ["profiler"],
    description = ["Runtime async-profiler control for Cassandra nodes"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProfilingStart::class,
        ProfilingStop::class,
        ProfilingStatus::class,
        ProfilingFetch::class,
        ProfilingFlamegraph::class,
    ],
)
class Profiler : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
