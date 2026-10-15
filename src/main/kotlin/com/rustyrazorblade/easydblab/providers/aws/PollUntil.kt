package com.rustyrazorblade.easydblab.providers.aws

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Runs [poll] under [RetryUtil.createPollUntilRetryConfig] and returns the first result [done]
 * accepts, or the last result when none is accepted within [maxAttempts] looks or before
 * [deadline]. Each failed look that is retried is logged; the last look's exception is rethrown.
 * An interrupted look ends the poll at once, with the thread's interrupt flag set again, and so
 * does a look whose exception [transient] rejects.
 *
 * @param operationName Name of the poll for logging and metrics
 * @param deadline when set, no look starts after it, however many attempts remain
 * @param transient which exceptions from a look are retried; every one but an interrupt by
 *   default. A poll that awaits only its result passes `{ false }`, so any error fails at once.
 */
fun <T> pollUntil(
    operationName: String,
    maxAttempts: Int,
    interval: Duration,
    deadline: Instant? = null,
    transient: (Throwable) -> Boolean = { true },
    done: (T) -> Boolean,
    poll: () -> T,
): T {
    val retry = Retry.of(operationName, RetryUtil.createPollUntilRetryConfig(maxAttempts, interval, done, deadline, transient))
    retry.eventPublisher.onRetry { event ->
        event.lastThrowable?.let { e -> log.warn(e) { "$operationName: a look failed; retrying" } }
    }
    // Checked, so a look that throws a checked exception (OkHttp's IOException) is retried too.
    return try {
        Retry.decorateCheckedSupplier(retry) { poll() }.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }
}
