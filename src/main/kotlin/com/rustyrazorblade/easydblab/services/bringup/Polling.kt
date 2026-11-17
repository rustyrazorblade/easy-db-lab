package com.rustyrazorblade.easydblab.services.bringup

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.time.Clock
import java.time.Duration

/**
 * Reads [look] every [interval] until [done] holds for what it read or [timeout] has passed, and
 * returns the last read. A wall-clock deadline, not an attempt count, bounds it, so it does not
 * stretch with how long one read takes. An exception from [look] is not retried: it propagates.
 */
fun <T> pollUntil(
    name: String,
    interval: Duration,
    timeout: Duration,
    clock: Clock = Clock.systemUTC(),
    done: (T) -> Boolean,
    look: () -> T,
): T {
    val deadline = clock.instant().plus(timeout)
    val config =
        RetryConfig
            .custom<T>()
            .maxAttempts(Int.MAX_VALUE)
            .intervalFunction { _ -> interval.toMillis() }
            .retryOnResult { !done(it) && clock.instant().isBefore(deadline) }
            .retryOnException { false }
            .build()
    return Retry.decorateSupplier(Retry.of(name, config), look).get()
}
