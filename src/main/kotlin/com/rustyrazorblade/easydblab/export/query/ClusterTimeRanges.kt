package com.rustyrazorblade.easydblab.export.query

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A span of time, start inclusive.
 *
 * @property start the first instant.
 * @property end the last instant.
 */
data class TimeRange(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(!end.isBefore(start)) { "A time range cannot end ($end) before it starts ($start)" }
    }

    /** Whether this range and [other] share an instant. */
    fun overlaps(other: TimeRange): Boolean = !start.isAfter(other.end) && !other.start.isAfter(end)
}

/**
 * When each selected cluster ran, from its `up` series, which every collector scrapes for as long as
 * the cluster runs, labelled or from before the label. One range query per tenant counts each
 * cluster's `up` samples in windows one step long, so a cluster that ran for minutes shows in its
 * window however long the step. A cluster's range is one step before its first window to its last,
 * widened by [MARGIN] (a teardown's last log lines come after the last scrape), and never past now. The
 * points are a whole number of steps before now, so the last window ends now.
 *
 * @property mimir the reader's Mimir.
 * @property clock now.
 */
class ClusterTimeRanges(
    private val mimir: MimirApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** The shortest step; finer would only cost more for no better bound. */
        const val MIN_STEP_SECONDS = 3600L

        /** The most points one query asks for, under Mimir's 11,000 per series. */
        const val MAX_POINTS = 10_000L

        /** How far a cluster's range reaches past its first and last `up` window. */
        val MARGIN: Duration = Duration.ofMinutes(10)
    }

    /**
     * The range of each of [clusters] of [tenant], searching from [since] (the tenant's oldest metric
     * block) to now.
     *
     * @throws ClusterNotFoundException when a cluster has no `up` sample in that time.
     */
    fun of(
        tenant: String,
        clusters: List<String>,
        since: Instant,
    ): Map<String, TimeRange> {
        val now = clock.instant()
        val span = Duration.between(since, now).seconds.coerceAtLeast(1)
        val step = maxOf(MIN_STEP_SECONDS, (span + MAX_POINTS - 1) / MAX_POINTS)
        // Points from a whole number of steps before now, so the last window ends now: a cluster that ran only in
        // the last minutes is in it, and the first window starts at or before the oldest block.
        val start = now.minusSeconds(((span + step - 1) / step) * step)
        val promql = "count by (cluster) (count_over_time(up{cluster=~\"${PromQlValues.anyOf(clusters)}\"}[${step}s]))"
        val found =
            mimir
                .queryRange(listOf(tenant), promql, start, now, step)
                .mapNotNull { series -> series.labels["cluster"]?.let { it to series.times } }
                .toMap()
        return clusters.associateWith { cluster ->
            val times = found[cluster].orEmpty().ifEmpty { throw ClusterNotFoundException(cluster, listOf(tenant)) }
            val end = Instant.ofEpochSecond(times.max()).plus(MARGIN)
            TimeRange(Instant.ofEpochSecond(times.min() - step).minus(MARGIN), if (end.isAfter(now)) now else end)
        }
    }
}
