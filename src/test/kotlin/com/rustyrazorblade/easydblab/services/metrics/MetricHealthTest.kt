package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricHealthTest : BaseKoinTest() {
    private val health = MetricHealth()
    private val boom = Result.failure<Unit>(IllegalStateException("HTTP 500"))

    @Test
    fun `a first success after the start is no transition`() {
        assertThat(health.record(StreamMetric.NODE_CPU, Result.success(Unit))).isEqualTo(MetricHealth.Transition.None)
    }

    @Test
    fun `the first failure is reported with its reason and later failures are not`() {
        assertThat(health.record(StreamMetric.NODE_CPU, boom)).isEqualTo(MetricHealth.Transition.Failed("HTTP 500"))
        assertThat(health.record(StreamMetric.NODE_CPU, boom)).isEqualTo(MetricHealth.Transition.None)
        assertThat(health.record(StreamMetric.NODE_CPU, boom)).isEqualTo(MetricHealth.Transition.None)
    }

    @Test
    fun `the next success after failures is a recovery, once`() {
        health.record(StreamMetric.NODE_CPU, boom)

        assertThat(health.record(StreamMetric.NODE_CPU, Result.success(Unit))).isEqualTo(MetricHealth.Transition.Recovered)
        assertThat(health.record(StreamMetric.NODE_CPU, Result.success(Unit))).isEqualTo(MetricHealth.Transition.None)
    }

    @Test
    fun `each metric has its own state`() {
        health.record(StreamMetric.NODE_CPU, boom)

        assertThat(health.record(StreamMetric.NODE_MEMORY, boom)).isEqualTo(MetricHealth.Transition.Failed("HTTP 500"))
    }

    @Test
    fun `a failure with no message is reported by its type`() {
        assertThat(health.record(StreamMetric.NODE_CPU, Result.failure<Unit>(java.io.IOException())))
            .isEqualTo(MetricHealth.Transition.Failed("IOException"))
    }
}
