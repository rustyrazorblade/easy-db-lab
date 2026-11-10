package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.PromQueryResult
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamMetricTest : BaseKoinTest() {
    private val allNames =
        listOf(
            "node.cpu",
            "node.memory",
            "node.disk_read",
            "node.disk_write",
            "node.filesystem_used",
            "node.network_receive",
            "node.network_transmit",
            "cassandra.read_p99",
            "cassandra.write_p99",
            "cassandra.read_ops",
            "cassandra.write_ops",
            "cassandra.compaction_pending",
            "cassandra.compaction_completed",
            "cassandra.compaction_bytes_written",
        )

    @Test
    fun `the catalogue holds exactly the 14 names`() {
        assertThat(StreamMetric.entries.map { it.metricName }).containsExactlyElementsOf(allNames)
    }

    @Test
    fun `the defaults are the 11 names and leave out memory, completed and bytes written`() {
        assertThat(StreamMetric.defaults().map { it.metricName })
            .containsExactly(
                "node.cpu",
                "node.disk_read",
                "node.disk_write",
                "node.filesystem_used",
                "node.network_receive",
                "node.network_transmit",
                "cassandra.read_p99",
                "cassandra.write_p99",
                "cassandra.read_ops",
                "cassandra.write_ops",
                "cassandra.compaction_pending",
            )
    }

    @Test
    fun `byName finds every name and nothing else`() {
        allNames.forEach { assertThat(StreamMetric.byName(it)?.metricName).isEqualTo(it) }
        assertThat(StreamMetric.byName("node.cpus")).isNull()
    }

    @Test
    fun `every selector in every query is scoped to the cluster`() {
        StreamMetric.entries.forEach { metric ->
            val promql = metric.promql("lab-x")
            val selectors = Regex("""[a-z_]+\{[^}]*}""").findAll(promql).map { it.value }.toList()
            assertThat(selectors).describedAs(metric.metricName).isNotEmpty()
            selectors.forEach { assertThat(it).describedAs(metric.metricName).contains("cluster=\"lab-x\"") }
        }
    }

    @Test
    fun `rates use the 1-minute window`() {
        StreamMetric.entries
            .map { it.promql("lab-x") }
            .filter { it.contains("rate(") }
            .forEach { assertThat(Regex("""\[\d+[smhd]]""").findAll(it).map { m -> m.value }.toSet()).containsExactly("[1m]") }
    }

    @Test
    fun `the gauges keep only samples younger than 30 seconds`() {
        listOf(StreamMetric.CASSANDRA_READ_P99, StreamMetric.CASSANDRA_WRITE_P99, StreamMetric.CASSANDRA_COMPACTION_PENDING)
            .forEach { assertThat(it.promql("lab-x")).contains("time() - timestamp(").contains("< 30") }
    }

    @Test
    fun `the p99 queries convert microseconds to milliseconds and name both range entities`() {
        val promql = StreamMetric.CASSANDRA_READ_P99.promql("lab-x")

        assertThat(promql).contains("cassandra_operation=\"read\"").contains("/ 1000")
        assertThat(promql).contains("\"cluster.min\"").contains("\"cluster.max\"").contains("> 0")
    }

    @Test
    fun `per-host samples use the host name and skip a series without one`() {
        val samples =
            StreamMetric.NODE_CPU.samples(
                listOf(result("12.5", "host_name" to "db0"), result("40", "host_name" to "app0"), result("99")),
            )

        assertThat(samples).containsExactly(
            Event.Metrics.Sample("node.cpu", "db0", 12.5),
            Event.Metrics.Sample("node.cpu", "app0", 40.0),
        )
    }

    @Test
    fun `cluster samples use the cluster entity`() {
        assertThat(StreamMetric.CASSANDRA_READ_OPS.samples(listOf(result("150"))))
            .containsExactly(Event.Metrics.Sample("cassandra.read_ops", "cluster", 150.0))
    }

    @Test
    fun `range samples take the entity label that the query writes`() {
        val samples =
            StreamMetric.CASSANDRA_WRITE_P99.samples(
                listOf(result("2", "entity" to "cluster.min"), result("9", "entity" to "cluster.max"), result("5")),
            )

        assertThat(samples).containsExactly(
            Event.Metrics.Sample("cassandra.write_p99", "cluster.min", 2.0),
            Event.Metrics.Sample("cassandra.write_p99", "cluster.max", 9.0),
        )
    }

    @Test
    fun `NaN, Inf and empty results give no sample and no zero`() {
        val samples =
            StreamMetric.NODE_DISK_READ.samples(
                listOf(result("NaN", "host_name" to "db0"), result("+Inf", "host_name" to "db1"), result("-Inf", "host_name" to "db2")),
            )

        assertThat(samples).isEmpty()
        assertThat(StreamMetric.CASSANDRA_READ_OPS.samples(emptyList())).isEmpty()
    }

    private fun result(
        value: String,
        vararg labels: Pair<String, String>,
    ): PromQueryResult = PromQueryResult(metric = labels.toMap(), value = listOf(JsonPrimitive(1.7e9), JsonPrimitive(value)))
}
