package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import java.time.Clock
import java.time.Duration

/**
 * How often a long phase reports its progress: every [every] units (items, or bytes) or every
 * [interval], whichever comes first, and once at the end.
 *
 * @property every the units between two reports.
 * @property interval the longest time between two reports while units finish.
 * @property clock the time.
 */
data class ProgressPolicy(
    val every: Long,
    val interval: Duration,
    val clock: Clock,
) {
    init {
        require(every > 0) { "every must be positive, was $every" }
    }

    /** A throttle of [total] units under this policy, calling [report] with the units done and [total]. */
    fun throttle(
        total: Long,
        report: (done: Long, total: Long) -> Unit,
    ): ProgressThrottle = ProgressThrottle(total, every, interval, clock, report)

    companion object {
        /** A trace export's fetch phase, in traces. */
        val TRACE_FETCH =
            ProgressPolicy(
                every =
                    Constants.Export.Query.TRACE_PROGRESS_EVERY_TRACES
                        .toLong(),
                interval = Duration.ofSeconds(Constants.Export.Query.TRACE_PROGRESS_INTERVAL_SECONDS),
                clock = Clock.systemUTC(),
            )
    }
}

/**
 * Counts the units of a long phase (items, or bytes) as they finish, on any thread, and calls
 * [report] every [every] units or once [interval] has passed since the last report (looked at as
 * units finish), whichever comes first, and once when all [total] are done. Each count is reported at
 * most once, and in order: [report] runs under the throttle's lock.
 *
 * @property total the units of the phase.
 * @property every the units between two reports.
 * @property interval the longest time between two reports while units finish.
 * @property clock the time.
 * @property report called with the units done and [total].
 */
class ProgressThrottle(
    private val total: Long,
    private val every: Long,
    private val interval: Duration,
    private val clock: Clock,
    private val report: (done: Long, total: Long) -> Unit,
) {
    private var done = 0L
    private var reportedAt = clock.instant()
    private var reportedDone = 0L

    /** [by] more units finished, one by default; reports when the policy says so. */
    @Synchronized
    fun advance(by: Long = 1) {
        done += by
        val now = clock.instant()
        if (done == total || done - reportedDone >= every || !now.isBefore(reportedAt.plus(interval))) {
            reportedDone = done
            reportedAt = now
            report(done, total)
        }
    }
}
