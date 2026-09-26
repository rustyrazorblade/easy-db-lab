package com.rustyrazorblade.easydblab.commands.logs

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.LogQl
import com.rustyrazorblade.easydblab.services.LokiQueryService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Query logs from Loki.
 *
 * This command provides a unified interface to query logs from all sources:
 * - Cassandra application logs (OTLP from the Java agent, `service_name="cassandra"`)
 * - Cassandra JVM GC log (/mnt/db1/cassandra/logs/gc.log, source `cassandra-gc`)
 * - ClickHouse logs (/mnt/db1/clickhouse/logs/)
 * - systemd/journald (cassandra.service, docker.service, etc.)
 * - System logs (/var/log/)
 * - EMR/Spark logs
 *
 * Without `--query`, the query is scoped to the current cluster, since clusters in one tenant share
 * Loki's store.
 *
 * Examples:
 * ```
 * # Query all of this cluster's logs from the last hour
 * easy-db-lab logs query
 *
 * # Filter by source
 * # `cassandra` selects the Java agent's OTLP stream; `cassandra-gc` the JVM GC log
 * easy-db-lab logs query --source cassandra
 * easy-db-lab logs query --source cassandra-gc
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
 * # Raw LogQL query, sent unchanged
 * easy-db-lab logs query -q '{service_name="cassandra"} |= "timed out"'
 * ```
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "query",
    description = ["Query logs from Loki"],
)
class LogsQuery : PicoBaseCommand() {
    private val lokiQueryService: LokiQueryService by inject()

    @Option(
        names = ["--source", "-s"],
        description = ["Log source: cassandra (application logs), cassandra-gc (JVM GC log), journald, system, tool-runner, emr"],
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
        description = ["Raw LogQL query, sent unchanged (not scoped to this cluster)"],
    )
    var rawQuery: String? = null

    override fun execute() {
        requireLocalTelemetryStack("logs query")

        // Build the query
        val query =
            rawQuery ?: LogQl.logsQuery(
                cluster = clusterState.clusterLabelName(),
                source = source,
                host = host,
                unit = unit,
                grep = grep,
            )

        eventBus.emit(Event.Logs.QueryInfo(query, since, limit))

        // Execute the query
        val logs =
            lokiQueryService
                .query(query, since, limit)
                .getOrElse { exception ->
                    eventBus.emit(Event.Logs.QueryFailed(exception.message ?: "Unknown error"))
                    eventBus.emit(
                        Event.Logs.QueryTips(
                            """
                            |Tips:
                            |  - Ensure the observability stack is deployed: easy-db-lab grafana update-config
                            |  - Check that Loki is running: kubectl get pods -l app.kubernetes.io/name=loki
                            """.trimMargin(),
                        ),
                    )
                    return
                }

        // Display results
        if (logs.isEmpty()) {
            eventBus.emit(Event.Logs.NoLogsFound)
        } else {
            eventBus.emit(Event.Logs.QueryResults(logs))
        }
    }
}
