package com.rustyrazorblade.easydblab.commands.logs

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
 * Import logs from the running cluster's VictoriaLogs to an external instance.
 *
 * Streams log data from the cluster's VictoriaLogs (on the control node) to a
 * target VictoriaLogs instance via the jsonline API.
 *
 * Example:
 * ```
 * easy-db-lab logs import --target http://victorialogs:9428
 * easy-db-lab logs import --target http://victorialogs:9428 --query 'source:cassandra'
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "import",
    description = ["Import logs from cluster VictoriaLogs to an external instance"],
)
class LogsImport : PicoBaseCommand() {
    private val victoriaStreamService: VictoriaStreamService by inject()

    @Option(names = ["--target"], description = ["Target VictoriaLogs URL (e.g., http://victorialogs:9428)"], required = true)
    lateinit var target: String

    @Option(names = ["--query"], description = ["LogsQL query (default: all logs)"])
    var query: String = Constants.Victoria.DEFAULT_LOGS_QUERY

    override fun execute() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Logs.NoControlNode)
            return
        }

        eventBus.emit(Event.Logs.ImportStarting(target))

        victoriaStreamService
            .streamLogs(controlHost, target, query)
            .onSuccess { result ->
                eventBus.emit(Event.Logs.ImportComplete(result.bytesTransferred))
            }.onFailure { exception ->
                eventBus.emit(Event.Logs.ImportFailed(exception.message ?: "Unknown error"))
            }
    }
}
