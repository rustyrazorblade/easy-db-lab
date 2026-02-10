package com.rustyrazorblade.cassandrametrics.collector

import com.rustyrazorblade.cassandrametrics.prometheus.MetricType

data class MetricColumn(
    val cqlColumn: String,
    val prometheusName: String,
    val type: MetricType,
    val help: String,
)

data class VirtualTableDefinition(
    val tableName: String,
    val labelColumns: List<String>,
    val metricColumns: List<MetricColumn>,
    val keyspaceFiltered: Boolean = false,
) {
    val query: String = "SELECT * FROM system_views.$tableName"
}
