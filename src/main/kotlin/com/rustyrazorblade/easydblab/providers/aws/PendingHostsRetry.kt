package com.rustyrazorblade.easydblab.providers.aws

import io.github.resilience4j.retry.RetryConfig
import java.time.Duration
import java.time.Instant

/**
 * Creates retry configuration for a readiness poll over many hosts: each attempt returns the
 * hosts still not ready, and the poll repeats [interval] apart while any remain and [deadline]
 * has not passed. A wall-clock deadline, not an attempt count, bounds the wait, so it does not
 * change with how long one sweep over 1,000 hosts takes. Exceptions are not retried: the
 * attempt itself decides which per-host failures mean "not ready yet".
 *
 * @param interval how long to wait between attempts
 * @param deadline the instant after which no further attempt starts
 */
fun <T> pendingHostsRetryConfig(
    interval: Duration,
    deadline: Instant,
): RetryConfig =
    RetryConfig
        .custom<Collection<T>>()
        .maxAttempts(Int.MAX_VALUE)
        .intervalFunction { _ -> interval.toMillis() }
        .retryOnResult { pending -> pending.isNotEmpty() && Instant.now().isBefore(deadline) }
        .retryOnException { false }
        .build()
