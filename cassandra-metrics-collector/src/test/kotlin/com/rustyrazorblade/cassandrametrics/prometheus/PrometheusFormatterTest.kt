package com.rustyrazorblade.cassandrametrics.prometheus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrometheusFormatterTest {
    @Test
    fun `format gauge metric with labels`() {
        val lines =
            listOf(
                MetricLine("cassandra_thread_pools_active_tasks", mapOf("name" to "ReadStage"), 12.0),
                MetricLine("cassandra_thread_pools_active_tasks", mapOf("name" to "MutationStage"), 3.0),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "cassandra_thread_pools_active_tasks",
                "Current active tasks",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("# HELP cassandra_thread_pools_active_tasks Current active tasks")
        assertThat(result).contains("# TYPE cassandra_thread_pools_active_tasks gauge")
        assertThat(result).contains("cassandra_thread_pools_active_tasks{name=\"ReadStage\"} 12")
        assertThat(result).contains("cassandra_thread_pools_active_tasks{name=\"MutationStage\"} 3")
    }

    @Test
    fun `format counter metric`() {
        val lines =
            listOf(
                MetricLine("cassandra_thread_pools_completed_tasks", mapOf("name" to "ReadStage"), 1000.0),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "cassandra_thread_pools_completed_tasks",
                "Total completed tasks",
                MetricType.COUNTER,
                lines,
            )

        assertThat(result).contains("# TYPE cassandra_thread_pools_completed_tasks counter")
        assertThat(result).contains("cassandra_thread_pools_completed_tasks{name=\"ReadStage\"} 1000")
    }

    @Test
    fun `format metric without labels`() {
        val lines =
            listOf(
                MetricLine("cassandra_settings_row_count", emptyMap(), 42.0),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "cassandra_settings_row_count",
                "Number of settings",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("cassandra_settings_row_count 42")
        assertThat(result).doesNotContain("{")
    }

    @Test
    fun `format metric with multiple labels`() {
        val lines =
            listOf(
                MetricLine(
                    "cassandra_coordinator_read_latency_p99th_ms",
                    mapOf("keyspace_name" to "my_ks", "table_name" to "my_table"),
                    2.5,
                ),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "cassandra_coordinator_read_latency_p99th_ms",
                "99th percentile",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains(
            "cassandra_coordinator_read_latency_p99th_ms{keyspace_name=\"my_ks\",table_name=\"my_table\"} 2.5",
        )
    }

    @Test
    fun `empty lines returns empty string`() {
        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help text",
                MetricType.GAUGE,
                emptyList(),
            )

        assertThat(result).isEmpty()
    }

    @Test
    fun `escapes special characters in label values`() {
        val lines =
            listOf(
                MetricLine("test_metric", mapOf("name" to "value with \"quotes\" and \\backslash"), 1.0),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("value with \\\"quotes\\\" and \\\\backslash")
    }

    @Test
    fun `formats integer values without decimal`() {
        val lines =
            listOf(
                MetricLine("test_metric", emptyMap(), 42.0),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("test_metric 42")
        assertThat(result).doesNotContain("42.0")
    }

    @Test
    fun `formats decimal values with decimal point`() {
        val lines =
            listOf(
                MetricLine("test_metric", emptyMap(), 0.95),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("test_metric 0.95")
    }

    @Test
    fun `handles NaN values`() {
        val lines =
            listOf(
                MetricLine("test_metric", emptyMap(), Double.NaN),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("test_metric NaN")
    }

    @Test
    fun `handles positive infinity`() {
        val lines =
            listOf(
                MetricLine("test_metric", emptyMap(), Double.POSITIVE_INFINITY),
            )

        val result =
            PrometheusFormatter.formatMetricFamily(
                "test_metric",
                "help",
                MetricType.GAUGE,
                lines,
            )

        assertThat(result).contains("test_metric +Inf")
    }
}
