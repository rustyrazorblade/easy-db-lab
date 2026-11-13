package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.PromQueryResult

/**
 * The 14 metrics `metrics stream` accepts: the catalogue.
 *
 * Each entry holds its name, how its result maps to sample entities ([scope]), whether it streams
 * when `--metrics` is not given ([defaultOn]), and its PromQL.  The queries come from the Grafana
 * dashboards (`system-overview`, `cassandra-overview`, `compaction-storage`) and the metric names
 * that the OTel Java agent's JMX rules export.  Every selector is scoped to the cluster, every rate
 * uses [Constants.LiveMetrics.RATE_WINDOW], and the gauges keep only samples younger than
 * [Constants.LiveMetrics.GAUGE_MAX_AGE_SECONDS], so a stopped JVM drops out in seconds.
 */
enum class StreamMetric(
    val metricName: String,
    val scope: MetricScope,
    val defaultOn: Boolean,
    private val query: (cluster: String) -> String,
) {
    NODE_CPU("node.cpu", MetricScope.PerHost, true, { c ->
        "100 * (1 - sum by (host_name) (${rate("system_cpu_time_seconds_total", c, "state=\"idle\"")}) / " +
            "sum by (host_name) (${rate("system_cpu_time_seconds_total", c)}))"
    }),
    NODE_MEMORY("node.memory", MetricScope.PerHost, false, { c ->
        "max by (host_name) (${selector("system_memory_usage_bytes", c, "state=\"used\"")})"
    }),
    NODE_DISK_READ("node.disk_read", MetricScope.PerHost, true, { c -> diskIo(c, "read") }),
    NODE_DISK_WRITE("node.disk_write", MetricScope.PerHost, true, { c -> diskIo(c, "write") }),
    NODE_FILESYSTEM_USED("node.filesystem_used", MetricScope.PerHost, true, { c ->
        val mount = "mountpoint=\"${Constants.K8s.DB_MOUNT_PATH}\""
        "100 * sum by (host_name) (${selector("system_filesystem_usage_bytes", c, "state=\"used\"", mount)}) / " +
            "sum by (host_name) (${selector("system_filesystem_usage_bytes", c, "state=~\"used|free\"", mount)})"
    }),
    NODE_NETWORK_RECEIVE("node.network_receive", MetricScope.PerHost, true, { c -> networkIo(c, "receive") }),
    NODE_NETWORK_TRANSMIT("node.network_transmit", MetricScope.PerHost, true, { c -> networkIo(c, "transmit") }),
    CASSANDRA_READ_P99("cassandra.read_p99", MetricScope.ClusterRange, true, { c -> p99Range(c, "read") }),
    CASSANDRA_WRITE_P99("cassandra.write_p99", MetricScope.ClusterRange, true, { c -> p99Range(c, "write") }),
    CASSANDRA_READ_OPS("cassandra.read_ops", MetricScope.Cluster, true, { c -> requestRate(c, "read") }),
    CASSANDRA_WRITE_OPS("cassandra.write_ops", MetricScope.Cluster, true, { c -> requestRate(c, "write") }),
    CASSANDRA_COMPACTION_PENDING("cassandra.compaction_pending", MetricScope.Cluster, true, { c ->
        "sum(${fresh(selector("cassandra_compaction_tasks_pending", c))})"
    }),
    CASSANDRA_COMPACTION_COMPLETED("cassandra.compaction_completed", MetricScope.Cluster, false, { c ->
        "sum(${rate("cassandra_compaction_tasks_completed_total", c)})"
    }),

    // The JMX rules export only the bytes compaction reads, so this counts input bytes; the docs say so.
    CASSANDRA_COMPACTION_BYTES_WRITTEN("cassandra.compaction_bytes_written", MetricScope.Cluster, false, { c ->
        "sum(${rate("cassandra_compaction_bytes_compacted_total", c)})"
    }),
    ;

    /** The PromQL for this metric, scoped to the cluster whose `cluster` label is [cluster]. */
    fun promql(cluster: String): String = query(cluster)

    /**
     * The samples of one successful query.  A series with no value, NaN or Inf gives no sample, and
     * neither does a series that [scope] cannot name.
     */
    fun samples(results: List<PromQueryResult>): List<Event.Metrics.Sample> =
        results.mapNotNull { result ->
            val entity = scope.entityOf(result.metric)
            val value = result.numericValue()
            if (entity == null || value == null) null else Event.Metrics.Sample(metricName, entity, value)
        }

    companion object {
        private val byMetricName = entries.associateBy { it.metricName }

        /** The entry named [name], or null when the catalogue has no such name. */
        fun byName(name: String): StreamMetric? = byMetricName[name]

        /** The names streamed when `--metrics` is not given, in catalogue order. */
        fun defaults(): List<StreamMetric> = entries.filter { it.defaultOn }
    }
}

private const val DISK_DEVICES = "device!~\"loop.*|.*p[0-9]+\""
private const val NETWORK_DEVICES = "device=~\"en.*|eth.*\""

private fun selector(
    metric: String,
    cluster: String,
    vararg matchers: String,
): String = "$metric{${(matchers.toList() + "cluster=\"$cluster\"").joinToString(", ")}}"

private fun rate(
    metric: String,
    cluster: String,
    vararg matchers: String,
): String = "rate(${selector(metric, cluster, *matchers)}[${Constants.LiveMetrics.RATE_WINDOW}])"

// Keeps the series of [series] whose newest sample is younger than the gauge age.  `timestamp()`
// keeps a sample's own time only when it is given a selector directly, so [series] must be one.
private fun fresh(series: String): String = "($series and (time() - timestamp($series) < ${Constants.LiveMetrics.GAUGE_MAX_AGE_SECONDS}))"

private fun diskIo(
    cluster: String,
    direction: String,
): String = "sum by (host_name) (${rate("system_disk_io_bytes_total", cluster, "direction=\"$direction\"", DISK_DEVICES)})"

private fun networkIo(
    cluster: String,
    direction: String,
): String = "sum by (host_name) (${rate("system_network_io_bytes_total", cluster, "direction=\"$direction\"", NETWORK_DEVICES)})"

private fun requestRate(
    cluster: String,
    operation: String,
): String = "sum(${rate("cassandra_client_request_count_total", cluster, "cassandra_operation=\"$operation\"")})"

// One query gives both ends, so the metric fails and recovers as one unit.  A node that reports 0
// served no requests and is left out of the minimum.
private fun p99Range(
    cluster: String,
    operation: String,
): String {
    val latency = fresh(selector("cassandra_client_request_latency_p99_microseconds", cluster, "cassandra_operation=\"$operation\""))
    val millis = Constants.LiveMetrics.MICROS_PER_MILLI
    return "label_replace(min($latency > 0), \"entity\", \"${Constants.LiveMetrics.CLUSTER_MIN_ENTITY}\", \"\", \"\") / $millis or " +
        "label_replace(max($latency), \"entity\", \"${Constants.LiveMetrics.CLUSTER_MAX_ENTITY}\", \"\", \"\") / $millis"
}
