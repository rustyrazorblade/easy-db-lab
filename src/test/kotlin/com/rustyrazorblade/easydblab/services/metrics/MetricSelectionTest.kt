package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.InvalidMetricName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test

class MetricSelectionTest : BaseKoinTest() {
    @Test
    fun `no --metrics gives the defaults`() {
        assertThat(MetricSelection.parse(null)).isEqualTo(MetricSelection.Selected(StreamMetric.defaults()))
    }

    @Test
    fun `names are selected in the order given`() {
        assertThat(MetricSelection.parse("node.memory,node.cpu"))
            .isEqualTo(MetricSelection.Selected(listOf(StreamMetric.NODE_MEMORY, StreamMetric.NODE_CPU)))
    }

    @Test
    fun `the empty entry and the unknown entry are both named with their positions`() {
        val result = MetricSelection.parse("node.cpu,,x")

        assertThat(result).isInstanceOf(MetricSelection.Rejected::class.java)
        assertThat((result as MetricSelection.Rejected).entries)
            .extracting(InvalidMetricName::position, InvalidMetricName::value)
            .containsExactly(tuple(2, ""), tuple(3, "x"))
    }

    @Test
    fun `a repeated name is used once, at its first position`() {
        assertThat(MetricSelection.parse("node.cpu,cassandra.read_ops,node.cpu"))
            .isEqualTo(MetricSelection.Selected(listOf(StreamMetric.NODE_CPU, StreamMetric.CASSANDRA_READ_OPS)))
    }

    @Test
    fun `a blank --metrics value is rejected`() {
        val result = MetricSelection.parse("  ")

        assertThat(result).isInstanceOf(MetricSelection.Rejected::class.java)
        assertThat((result as MetricSelection.Rejected).entries.map { it.position }).containsExactly(1)
    }

    @Test
    fun `spaces around a name are ignored`() {
        assertThat(MetricSelection.parse("node.cpu, node.memory"))
            .isEqualTo(MetricSelection.Selected(listOf(StreamMetric.NODE_CPU, StreamMetric.NODE_MEMORY)))
    }
}
