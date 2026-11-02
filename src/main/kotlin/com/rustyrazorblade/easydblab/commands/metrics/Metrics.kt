package com.rustyrazorblade.easydblab.commands.metrics

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for live metrics.
 *
 * `metrics stream` publishes chosen metrics from the cluster's Mimir to Redis every 5 seconds, for a
 * live display such as a stream overlay.  `metrics list` prints the names it accepts, so a caller
 * (or Claude Code, from a test plan) can choose them.
 */
@Command(
    name = "metrics",
    description = ["Stream live cluster metrics to Redis"],
    mixinStandardHelpOptions = true,
    subcommands = [
        MetricsStream::class,
        MetricsList::class,
    ],
)
class Metrics : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
