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
 *
 * @param operationName Name of the poll for logging and metrics
 * @param deadline when set, no look starts after it, however many attempts remain
 */
fun <T> pollUntil(
    operationName: String,
    maxAttempts: Int,
    interval: Duration,
    deadline: Instant? = null,
    done: (T) -> Boolean,
    poll: () -> T,
): T {
    val retry = Retry.of(operationName, RetryUtil.createPollUntilRetryConfig(maxAttempts, interval, done, deadline))
    retry.eventPublisher.onRetry { event ->
        event.lastThrowable?.let { e -> log.warn(e) { "$operationName: a look failed; retrying" } }
    }
    return Retry.decorateSupplier(retry, poll).get()
}
