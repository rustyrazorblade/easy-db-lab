package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.Constants
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.io.File
import java.time.Duration

/** How one `packer build` run ended. */
sealed interface PackerBuildOutcome {
    /** Packer exited 0: the AMI is registered. */
    data object Succeeded : PackerBuildOutcome

    /** Packer exited non-zero, for [failure]'s reason; [logFile] is the attempt's Packer debug log. */
    data class Failed(
        val failure: PackerFailure,
        val logFile: File,
    ) : PackerBuildOutcome
}

/** What became of a failed attempt's build instance. */
sealed interface BuilderCleanup {
    /** No build instance was found to clean up. */
    data object NotFound : BuilderCleanup

    /** Left running for --keep-on-error. */
    data object Kept : BuilderCleanup

    data object Terminated : BuilderCleanup

    /** The terminate call failed, so [instanceId] may still be running. */
    data class TerminateFailed(
        val instanceId: String,
    ) : BuilderCleanup
}

/**
 * Runs an AMI build again when its SSH session to the builder dropped.
 *
 * A dropped session is a transport failure, not a fault of the build, and each attempt launches a
 * new builder from scratch, so running it again is safe. Any other failure ends the build at once:
 * running a failing script again only repeats it. The policy is a resilience4j retry on the result.
 *
 * @param maxAttempts total attempts, the first included
 * @param delay pause before the next attempt, after a dropped one's builder is torn down
 */
class PackerBuildAttempts(
    private val maxAttempts: Int = Constants.Packer.MAX_BUILD_ATTEMPTS,
    private val delay: Duration = Duration.ofSeconds(Constants.Packer.BUILD_RETRY_DELAY_SECONDS),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, got $maxAttempts" }
    }

    /**
     * Runs [build] until it succeeds, fails for a reason other than a dropped session, or uses up
     * the attempts, and returns the last outcome.
     *
     * @param build runs one attempt, given its 1-based number
     * @param afterFailure called after each failed attempt with its number, its outcome and whether
     *   another attempt follows; it cleans up the failed attempt's builder and reports the failure
     */
    fun run(
        build: (attempt: Int) -> PackerBuildOutcome,
        afterFailure: (attempt: Int, failed: PackerBuildOutcome.Failed, willRetry: Boolean) -> Unit,
    ): PackerBuildOutcome {
        val config =
            RetryConfig
                .custom<PackerBuildOutcome>()
                .maxAttempts(maxAttempts)
                .intervalFunction { _ -> delay.toMillis() }
                .retryOnResult(::isSessionDrop)
                // Only a dropped session runs again: a throw from an attempt or its cleanup is final.
                .retryOnException { false }
                .build()
        var attempt = 0
        return Retry
            .of("packer-build", config)
            .executeSupplier {
                attempt++
                build(attempt).also { outcome ->
                    if (outcome is PackerBuildOutcome.Failed) {
                        afterFailure(attempt, outcome, isSessionDrop(outcome) && attempt < maxAttempts)
                    }
                }
            }
    }

    private fun isSessionDrop(outcome: PackerBuildOutcome): Boolean =
        outcome is PackerBuildOutcome.Failed && outcome.failure is PackerFailure.SessionDropped
}
