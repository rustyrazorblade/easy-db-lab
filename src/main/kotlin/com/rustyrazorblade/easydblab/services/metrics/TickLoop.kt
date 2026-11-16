package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants
import java.time.Clock
import java.time.Duration

/**
 * Runs `metrics stream` cycles on the calling thread, one per tick at `start + k * interval`.
 *
 * A tick that comes while a cycle still runs is skipped, so cycles never overlap and a slow cycle
 * does not cause a burst of late ones.  Running on the command thread keeps the command name in
 * every event the cycle emits.
 *
 * @param clock the time source; tests replace it
 * @param sleeper waits for a duration; an interrupt (a stop signal) ends the loop
 * @param interval the time between ticks
 */
class TickLoop(
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val interval: Duration = Duration.ofSeconds(Constants.LiveMetrics.COLLECTION_INTERVAL_SECONDS),
) {
    /**
     * Runs [cycle] on each tick until [isStopped] is true, [cycle] returns false, or a wait is
     * interrupted.
     */
    fun run(
        isStopped: () -> Boolean,
        cycle: () -> Boolean,
    ) {
        val start = clock.instant()
        while (!isStopped()) {
            if (!cycle() || isStopped()) return
            val now = clock.instant()
            val ticksPassed = Duration.between(start, now).toMillis() / interval.toMillis()
            val nextTick = start.plus(interval.multipliedBy(ticksPassed + 1))
            try {
                sleeper(Duration.between(now, nextTick))
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
