package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.VictoriaStreamService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Import metrics from the running cluster's VictoriaMetrics to an external instance.
 *
 * Streams metrics data via the native export/import API from the cluster's
 * VictoriaMetrics (on the control node) to a target VictoriaMetrics instance.
 *
 * Example:
 * ```
 * easy-db-lab metrics import --target http://victoria:8428
 * easy-db-lab metrics import --target http://victoria:8428 --match '{job="cassandra"}'
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "import",
    description = ["Import metrics from cluster VictoriaMetrics to an external instance"],
)
class MetricsImport : PicoBaseCommand() {
    private val victoriaStreamService: VictoriaStreamService by inject()

    @Option(names = ["--target"], description = ["Target VictoriaMetrics URL (e.g., http://victoria:8428)"], required = true)
    lateinit var target: String

    @Option(names = ["--match"], description = ["Metric selector (default: all metrics)"])
    var match: String = Constants.Victoria.DEFAULT_METRICS_MATCH

    override fun execute() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Metrics.NoControlNode)
            return
        }

        eventBus.emit(Event.Metrics.ImportStarting(target))

        victoriaStreamService
            .streamMetrics(controlHost, target, match)
            .onSuccess { result ->
                eventBus.emit(Event.Metrics.ImportComplete(result.bytesTransferred))
            }.onFailure { exception ->
                eventBus.emit(Event.Metrics.ImportFailed(exception.message ?: "Unknown error"))
            }
    }
}
