package com.rustyrazorblade.easydblab.commands.logs

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Query logs from Victoria Logs.
 *
 * This command provides a unified interface to query logs from all sources:
 * - Cassandra logs (/var/log/cassandra/)
 * - ClickHouse logs (/mnt/db1/clickhouse/logs/)
 * - systemd/journald (cassandra.service, docker.service, etc.)
 * - System logs (/var/log/)
 * - EMR/Spark logs (from S3 via SQS notifications)
 *
 * Examples:
 * ```
 * # Query all logs from last hour
 * easy-db-lab logs query
 *
 * # Filter by source
 * easy-db-lab logs query --source cassandra
 * easy-db-lab logs query --source emr
 *
 * # Filter by host
 * easy-db-lab logs query --source cassandra --host db0
 *
 * # Filter by systemd unit
 * easy-db-lab logs query --source systemd --unit docker.service
 *
 * # Search for text
 * easy-db-lab logs query --grep "OutOfMemory"
 *
 * # Time range and limit
 * easy-db-lab logs query --since 30m --limit 500
 *
 * # Raw Victoria Logs query
 * easy-db-lab logs query -q 'source:cassandra AND host:db0'
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "query",
    description = ["Query logs from Victoria Logs"],
)
class LogsQuery : PicoBaseCommand() {
    private val victoriaLogsService: VictoriaLogsService by inject()

    @Option(
        names = ["--source", "-s"],
        description = ["Log source: emr, cassandra, clickhouse, systemd, system"],
    )
    var source: String? = null

    @Option(
        names = ["--host", "-H"],
        description = ["Filter by hostname (db0, app0, control0)"],
    )
    var host: String? = null

    @Option(
        names = ["--unit"],
        description = ["systemd unit name (e.g., cassandra.service, docker.service)"],
    )
    var unit: String? = null

    @Option(
        names = ["--since"],
        description = ["Time range: 1h, 30m, 1d (default: 1h)"],
    )
    var since: String = "1h"

    @Suppress("MagicNumber")
    @Option(
        names = ["--limit", "-n"],
        description = ["Max lines to return (default: 100)"],
    )
    var limit: Int = 100

    @Option(
        names = ["--grep", "-g"],
        description = ["Filter logs containing text"],
    )
    var grep: String? = null

    @Option(
        names = ["--query", "-q"],
        description = ["Raw Victoria Logs query (LogsQL syntax)"],
    )
    var rawQuery: String? = null

    override fun execute() {
        // Build the query
        val query = rawQuery ?: buildQuery()

        outputHandler.handleMessage("Query: $query, Time range: $since, Limit: $limit\n")

        // Execute the query
        val logs =
            victoriaLogsService
                .query(query, since, limit)
                .getOrElse { exception ->
                    outputHandler.handleError("Failed to query logs: ${exception.message}")
                    outputHandler.handleMessage(
                        """
                        |Tips:
                        |  - Ensure observability stack is deployed: easy-db-lab k8 apply
                        |  - Check if Victoria Logs is running: kubectl get pods
                        """.trimMargin(),
                    )
                    return
                }

        // Display results
        if (logs.isEmpty()) {
            outputHandler.handleMessage("No logs found matching the query.")
        } else {
            outputHandler.handleMessage(logs.joinToString("\n"))
            outputHandler.handleMessage("\nFound ${logs.size} log entries.")
        }
    }

    /**
     * Builds a LogsQL query from the command options.
     */
    private fun buildQuery(): String {
        val parts = mutableListOf<String>()

        source?.let { parts.add("source:$it") }
        host?.let { parts.add("host:$it") }
        unit?.let { parts.add("unit:$it") }
        grep?.let { parts.add("\"$it\"") }

        return parts.joinToString(" AND ").ifEmpty { "*" }
    }
}
