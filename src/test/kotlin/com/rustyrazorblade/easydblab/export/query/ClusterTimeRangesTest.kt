package com.rustyrazorblade.easydblab.export.query

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * When each selected cluster ran, from its `up` series: one range query per tenant, counting each
 * cluster's `up` samples in windows of one step, so a cluster that ran for minutes is not stepped
 * over. A cluster's logs, traces and metric blocks are read only inside its range.
 */
class ClusterTimeRangesTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var times: Map<String, List<Long>> = emptyMap()
    private val backends =
        FakeBackends { _ ->
            FakeBackends.prometheus(
                """{"resultType":"matrix","result":[${times.entries.joinToString(",") { (cluster, ts) ->
                    """{"metric":{"cluster":"$cluster"},"values":[${ts.joinToString(",") { "[$it,\"1\"]" }}]}"""
                }}]}""",
            )
        }
    private val ranges = ClusterTimeRanges(MimirApi(backends::forTenant, clock), clock)

    @Test
    fun `each cluster's range runs from one step before its first window to its last, with the margin, from one query`() {
        val since = now.minus(Duration.ofDays(10))
        val hour = 3600L
        times = mapOf("x-1" to listOf(since.epochSecond + 5 * hour, since.epochSecond + 7 * hour), "y-2" to listOf(now.epochSecond))

        val found = ranges.of("acme", listOf("x-1", "y-2"), since)

        val margin = ClusterTimeRanges.MARGIN
        assertThat(found.getValue("x-1"))
            .isEqualTo(TimeRange(since.plusSeconds(4 * hour).minus(margin), since.plusSeconds(7 * hour).plus(margin)))
        // Never past now.
        assertThat(found.getValue("y-2").end).isEqualTo(now)
        val call = backends.calls.single()
        assertThat(call.tenant).isEqualTo("acme")
        assertThat(call.route).isEqualTo("/prometheus/api/v1/query_range")
        assertThat(call.param("query")).isEqualTo("count by (cluster) (count_over_time(up{cluster=~\"x-1|y-2\"}[3600s]))")
        assertThat(call.param("step")).isEqualTo("3600")
        assertThat(call.param("start")).isEqualTo(since.epochSecond.toString())
        assertThat(call.param("end")).isEqualTo(now.epochSecond.toString())
    }

    @Test
    fun `a long history takes a longer step, so the query stays under Mimir's points limit`() {
        val since = now.minus(Duration.ofDays(3 * 365))
        times = mapOf("x-1" to listOf(now.epochSecond))

        ranges.of("acme", listOf("x-1"), since)

        val step = backends.calls.single().param("step")!!.toLong()
        assertThat(Duration.between(since, now).seconds / step).isLessThanOrEqualTo(ClusterTimeRanges.MAX_POINTS)
        assertThat(backends.calls.single().param("query")).contains("[${step}s]")
    }

    @Test
    fun `a cluster with no up sample in the range fails naming it and the tenant`() {
        times = mapOf("x-1" to listOf(now.epochSecond))

        assertThatThrownBy { ranges.of("acme", listOf("x-1", "gone-2"), now.minus(Duration.ofDays(1))) }
            .isInstanceOf(ClusterNotFoundException::class.java)
            .hasMessageContaining("gone-2")
            .hasMessageContaining("acme")
    }
}
