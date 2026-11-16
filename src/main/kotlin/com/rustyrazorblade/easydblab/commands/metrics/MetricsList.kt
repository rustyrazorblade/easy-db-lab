package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.metrics.StreamMetric
import picocli.CommandLine.Command

/**
 * Prints every name `metrics stream --metrics` accepts, one per line.  A read-only display command:
 * it needs no cluster and emits no events.
 */
@Command(
    name = "list",
    description = ["List the metric names that 'metrics stream' accepts"],
)
class MetricsList : PicoBaseCommand() {
    override fun execute() {
        println(StreamMetric.entries.joinToString("\n") { it.metricName })
    }
}
