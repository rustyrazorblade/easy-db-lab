package com.rustyrazorblade.cassandrametrics.collector

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.rustyrazorblade.cassandrametrics.prometheus.MetricLine
import com.rustyrazorblade.cassandrametrics.prometheus.PrometheusFormatter
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

private val SYSTEM_KEYSPACES =
    setOf(
        "system",
        "system_schema",
        "system_auth",
        "system_distributed",
        "system_traces",
        "system_views",
        "system_virtual_schema",
    )

class CqlQueryExecutor(
    private val session: CqlSession,
    private val includeSystemKeyspaces: Boolean,
) {
    fun collect(table: VirtualTableDefinition): String {
        val resultSet = session.execute(table.query)
        val rows = resultSet.all()

        if (isRowCountOnly(table)) {
            return formatRowCount(table, rows)
        }

        if (isRepairTable(table)) {
            return formatRepairTable(table, rows)
        }

        return formatStandardTable(table, rows)
    }

    private fun isRowCountOnly(table: VirtualTableDefinition): Boolean =
        table.metricColumns.size == 1 &&
            table.metricColumns[0].cqlColumn == "row_count" &&
            table.labelColumns.isEmpty()

    private fun isRepairTable(table: VirtualTableDefinition): Boolean =
        table.metricColumns.size == 1 &&
            table.metricColumns[0].cqlColumn == "row_count" &&
            table.labelColumns == listOf("status")

    private fun formatRowCount(
        table: VirtualTableDefinition,
        rows: List<Row>,
    ): String {
        val metric = table.metricColumns[0]
        val line = MetricLine(metric.prometheusName, emptyMap(), rows.size.toDouble())
        return PrometheusFormatter.formatMetricFamily(
            metric.prometheusName,
            metric.help,
            metric.type,
            listOf(line),
        )
    }

    private fun formatRepairTable(
        table: VirtualTableDefinition,
        rows: List<Row>,
    ): String {
        val metric = table.metricColumns[0]
        val countsByStatus = mutableMapOf<String, Int>()

        for (row in rows) {
            val status = safeGetString(row, "status") ?: "unknown"
            countsByStatus[status] = (countsByStatus[status] ?: 0) + 1
        }

        if (countsByStatus.isEmpty()) {
            countsByStatus["total"] = 0
        }

        val lines =
            countsByStatus.map { (status, count) ->
                MetricLine(metric.prometheusName, mapOf("status" to status), count.toDouble())
            }
        return PrometheusFormatter.formatMetricFamily(
            metric.prometheusName,
            metric.help,
            metric.type,
            lines,
        )
    }

    private fun formatStandardTable(
        table: VirtualTableDefinition,
        rows: List<Row>,
    ): String {
        val sb = StringBuilder()

        for (metricCol in table.metricColumns) {
            val lines = mutableListOf<MetricLine>()

            for (row in rows) {
                if (table.keyspaceFiltered && !includeSystemKeyspaces) {
                    val ks = safeGetString(row, "keyspace_name")
                    if (ks != null && ks in SYSTEM_KEYSPACES) continue
                }

                val labels = buildLabels(table.labelColumns, row)
                val value = safeGetDouble(row, metricCol.cqlColumn) ?: continue

                lines.add(MetricLine(metricCol.prometheusName, labels, value))
            }

            sb.append(
                PrometheusFormatter.formatMetricFamily(
                    metricCol.prometheusName,
                    metricCol.help,
                    metricCol.type,
                    lines,
                ),
            )
        }

        return sb.toString()
    }

    private fun buildLabels(
        labelColumns: List<String>,
        row: Row,
    ): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        for (col in labelColumns) {
            val value = safeGetString(row, col) ?: ""
            labels[sanitizeLabelName(col)] = value
        }
        return labels
    }

    private fun safeGetString(
        row: Row,
        column: String,
    ): String? =
        try {
            val idx = row.columnDefinitions.firstIndexOf(column)
            if (idx < 0) {
                null
            } else {
                val obj = row.getObject(idx)
                obj?.toString()
            }
        } catch (e: Exception) {
            log.debug { "Could not read column $column: ${e.message}" }
            null
        }

    private fun safeGetDouble(
        row: Row,
        column: String,
    ): Double? =
        try {
            val idx = row.columnDefinitions.firstIndexOf(column)
            if (idx < 0) {
                null
            } else {
                val obj = row.getObject(idx)
                when (obj) {
                    null -> null
                    is Number -> obj.toDouble()
                    is Boolean -> if (obj) 1.0 else 0.0
                    else -> null
                }
            }
        } catch (e: Exception) {
            log.debug { "Could not read numeric column $column: ${e.message}" }
            null
        }

    private fun sanitizeLabelName(name: String): String = name.replace(Regex("[^a-zA-Z0-9_]"), "_")
}
