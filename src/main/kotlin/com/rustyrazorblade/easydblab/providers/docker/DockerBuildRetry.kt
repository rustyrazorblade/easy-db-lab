package com.rustyrazorblade.easydblab.providers.docker

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DockerException
import com.rustyrazorblade.easydblab.kernel.causeChain
import io.github.resilience4j.retry.RetryConfig

/**
 * The retry policy for building a Docker image.
 *
 * Under Docker's containerd image store, every image prune first deletes the content leases that
 * pulls and builds in progress hold, whatever the prune's filter. A build whose base is being
 * unpacked at that moment fails with containerd's "lease does not exist", though nothing is wrong
 * with it. Testcontainers' JVM-hook reaper prunes images at each test JVM's exit, so a parallel test
 * run hits this. The policy retries only that failure; any other build failure (a `RUN` step that
 * exits non-zero, a base image that does not exist) would fail the same way again.
 *
 * It is its own object, not a member of `RetryUtil`, because it is the only Docker policy that
 * matches on the daemon's message rather than on an exception type.
 */
object DockerBuildRetry {
    /** Three attempts with exponential backoff (1s, 2s), retrying only a lost content lease. */
    fun <T> config(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_DOCKER_RETRIES)
            .intervalFunction { attemptCount ->
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException(::isLeaseLost)
            .build()

    /**
     * Whether the daemon's error, below the project [DockerException] that `Docker.buildImage`
     * wraps it in, carries containerd's lease-lost message. The wrapper is skipped because its
     * message holds the build's output, and a step that prints the phrase and then fails is the
     * step's own failure.
     */
    private fun isLeaseLost(throwable: Throwable): Boolean =
        throwable
            .causeChain()
            .dropWhile { it is DockerException }
            .any { Constants.Docker.LEASE_LOST_MESSAGE in it.message.orEmpty() }
}
