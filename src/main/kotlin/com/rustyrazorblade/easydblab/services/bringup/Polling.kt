package com.rustyrazorblade.easydblab.services.bringup

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * Reads [look] every [interval] until [done] holds for what it read or [timeout] has passed, and
 * returns the last read. A wall-clock deadline, not an attempt count, bounds it, so it does not
 * stretch with how long one read takes, and the wait before a read is cut to the time left, so the
 * poll never sleeps past the deadline. An exception from [look] is not retried: it propagates.
 *
 * @throws InterruptedException when the thread is interrupted while it waits between reads, with
 *   the interrupt flag set again (resilience4j reports that as a [CancellationException])
 */
fun <T> pollUntil(
    name: String,
    interval: Duration,
    timeout: Duration,
    clock: Clock = Clock.systemUTC(),
    done: (T) -> Boolean,
    look: () -> T,
): T = pollUntilDeadline(name, interval, clock.instant().plus(timeout), clock, done, look)

/**
 * [pollUntil] up to a [deadline] the caller already holds, so a caller that also bounds each read
 * by the same deadline has one clock, not two.
 */
fun <T> pollUntilDeadline(
    name: String,
    interval: Duration,
    deadline: Instant,
    clock: Clock = Clock.systemUTC(),
    done: (T) -> Boolean,
    look: () -> T,
): T {
    val config =
        RetryConfig
            .custom<T>()
            .maxAttempts(Int.MAX_VALUE)
            .intervalFunction { _ -> minOf(interval, Duration.between(clock.instant(), deadline)).toMillis().coerceAtLeast(0L) }
            .retryOnResult { !done(it) && clock.instant().isBefore(deadline) }
            .retryOnException { false }
            .build()
    return try {
        Retry.decorateSupplier(Retry.of(name, config), look).get()
    } catch (e: CancellationException) {
        if (!Thread.currentThread().isInterrupted) throw e
        throw InterruptedException("$name was interrupted between reads").apply { initCause(e) }
    }
}
