package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import java.time.Clock
import java.time.Duration

/**
 * How often a long phase reports its progress: every [everyItems] items or every [interval],
 * whichever comes first, and once at the end.
 *
 * @property everyItems the items between two reports.
 * @property interval the longest time between two reports while items finish.
 * @property clock the time.
 */
data class ProgressPolicy(
    val everyItems: Int,
    val interval: Duration,
    val clock: Clock,
) {
    init {
        require(everyItems > 0) { "everyItems must be positive, was $everyItems" }
    }

    /** A throttle of [total] items under this policy, calling [report] with the items done and [total]. */
    fun throttle(
        total: Int,
        report: (done: Int, total: Int) -> Unit,
    ): ProgressThrottle = ProgressThrottle(total, everyItems, interval, clock, report)

    companion object {
        /** A trace export's fetch phase. */
        val TRACE_FETCH =
            ProgressPolicy(
                everyItems = Constants.Export.Query.TRACE_PROGRESS_EVERY_TRACES,
                interval = Duration.ofSeconds(Constants.Export.Query.TRACE_PROGRESS_INTERVAL_SECONDS),
                clock = Clock.systemUTC(),
            )
    }
}

/**
 * Counts the items of a long phase as they finish, on any thread, and calls [report] every
 * [everyItems] items or once [interval] has passed since the last report (looked at as an item
 * finishes), whichever comes first, and once when all [total] are done. Each count is reported at most
 * once, and in order: [report] runs under the throttle's lock.
 *
 * @property total the items of the phase.
 * @property everyItems the items between two reports.
 * @property interval the longest time between two reports while items finish.
 * @property clock the time.
 * @property report called with the items done and [total].
 */
class ProgressThrottle(
    private val total: Int,
    private val everyItems: Int,
    private val interval: Duration,
    private val clock: Clock,
    private val report: (done: Int, total: Int) -> Unit,
) {
    private var done = 0
    private var reportedAt = clock.instant()
    private var reportedDone = 0

    /** One more item finished; reports when the policy says so. */
    @Synchronized
    fun advance() {
        done++
        val now = clock.instant()
        if (done == total || done - reportedDone >= everyItems || !now.isBefore(reportedAt.plus(interval))) {
            reportedDone = done
            reportedAt = now
            report(done, total)
        }
    }
}
