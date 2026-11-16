package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException
import java.io.IOException
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Polls like [pollUntil] for at most [timeout] and returns the first result [done] accepts. A look
 * that fails with a transient error ([isTransientAwsFailure]: a throttle, a 5xx, a network failure)
 * is retried within the same budget, so one throttled describe call does not fail the wait; any
 * other failure (access denied, expired credentials, a bad request, a defect) ends the wait at
 * once with its own exception. When no result satisfies [done] in time, throws
 * [AwsTimeoutException]; this is the one exception every wait raises when it does not converge. A
 * look that keeps failing transiently surfaces its own exception, since that is a failure and not
 * a timeout. [done] must have no side effects: it is checked again on the final result.
 *
 * @param what what the wait is for, as it reads in "Timeout waiting for [what] after ...ms"
 */
fun <T> pollUntilOrTimeout(
    what: String,
    timeout: Duration,
    interval: Duration,
    done: (T) -> Boolean,
    poll: () -> T,
): T {
    val maxAttempts =
        when {
            interval.isZero -> Int.MAX_VALUE
            else -> (timeout.toMillis() / interval.toMillis() + 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        }
    val result =
        pollUntil("wait-for-$what", maxAttempts, interval, Instant.now().plus(timeout), ::isTransientAwsFailure, done, poll)
    if (!done(result)) {
        throw AwsTimeoutException("Timeout waiting for $what after ${timeout.toMillis()}ms")
    }
    return result
}

/**
 * Whether a failed AWS look is worth repeating: a throttle, a server error, or a failure to reach
 * the service. Everything else will fail the same way on the next look, so a wait that retried it
 * would hold `up` or `down` for its whole timeout before the real cause surfaced.
 */
internal fun isTransientAwsFailure(failure: Throwable): Boolean =
    when (failure) {
        is AwsServiceException -> failure.isThrottlingException || failure.statusCode() >= Constants.HttpStatus.SERVER_ERROR_MIN
        is SdkException -> failure.retryable() || failure.cause is IOException
        is IOException -> true
        else -> false
    }

/**
 * Runs [poll] under [RetryUtil.createPollUntilRetryConfig] and returns the first result [done]
 * accepts, or the last result when none is accepted within [maxAttempts] looks or before
 * [deadline]. Each failed look that is retried is logged; the last look's exception is rethrown.
 * An interrupted look ends the poll at once, with the thread's interrupt flag set again.
 *
 * @param operationName Name of the poll for logging and metrics
 * @param deadline when set, no look starts after it, however many attempts remain
 * @param isTransient which failed looks are retried; any other failure ends the poll at once
 */
fun <T> pollUntil(
    operationName: String,
    maxAttempts: Int,
    interval: Duration,
    deadline: Instant? = null,
    isTransient: (Throwable) -> Boolean = { true },
    done: (T) -> Boolean,
    poll: () -> T,
): T {
    val retry =
        Retry.of(
            operationName,
            RetryUtil.createPollUntilRetryConfig(maxAttempts, interval, done, deadline, isTransient = isTransient),
        )
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
