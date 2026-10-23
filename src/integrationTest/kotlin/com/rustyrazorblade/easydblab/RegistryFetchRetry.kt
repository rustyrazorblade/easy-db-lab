package com.rustyrazorblade.easydblab

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.io.IOException
import java.time.Duration

/**
 * Retries a registry fetch of [RegistryImageSource] when the registry connection fails.
 *
 * A registry or its CDN sometimes stalls a blob download until the client's read timeout ends it.
 * In a full-suite run, while the other forks start their containers, that happened often enough
 * to fail the run. Jib keeps the layers it already downloaded, so a new attempt fetches only
 * what the stalled one did not finish. A failure is transient when an [IOException] is anywhere in
 * its cause chain (Jib wraps one in an `ExecutionException`); any other failure, such as an
 * incomplete archive, fails at once, and so does a fetch whose thread was interrupted.
 */
class RegistryFetchRetry(
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    /** Runs [fetch] of [image], again after [interval] while it fails transiently, up to [maxAttempts] times. */
    fun <T> run(
        image: String,
        fetch: () -> T,
    ): T {
        val config =
            RetryConfig
                .custom<T>()
                .maxAttempts(maxAttempts)
                .waitDuration(interval)
                .retryOnException(::isTransient)
                .build()
        return Retry.of("registry-fetch $image", config).executeCallable(fetch)
    }

    private fun isTransient(failure: Throwable): Boolean =
        !Thread.currentThread().isInterrupted && generateSequence(failure) { it.cause }.any { it is IOException }

    private companion object {
        const val DEFAULT_ATTEMPTS = 4
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(2)
    }
}
