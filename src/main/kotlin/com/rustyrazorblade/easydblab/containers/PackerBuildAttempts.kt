package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingException
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

/**
 * What became of a failed attempt's build instances. The next attempt may launch its own builder only
 * when no earlier one can still be running: after [NotFound] or [Terminated].
 */
sealed interface BuilderCleanup {
    /** The builder may still be running, so a new attempt must not launch beside it. */
    sealed interface Unresolved : BuilderCleanup {
        /** Why the builder may still be running, for the failure message. */
        val reason: String
    }

    /** The packer VPC held no build instance. */
    data object NotFound : BuilderCleanup

    /** Every build instance in the packer VPC, [instanceIds], was terminated. */
    data class Terminated(
        val instanceIds: List<String>,
    ) : BuilderCleanup

    /** [instanceIds] were left running for --keep-on-error. */
    data class Kept(
        val instanceIds: List<String>,
    ) : BuilderCleanup

    /** The build instances could not be listed. */
    data class LookupFailed(
        val error: String,
    ) : Unresolved {
        override val reason: String get() = "the packer VPC's instances could not be listed ($error)"
    }

    /** The terminate call failed, so [instanceIds] may still be running. */
    data class TerminateFailed(
        val instanceIds: List<String>,
        val error: String,
    ) : Unresolved {
        override val reason: String get() = "${instanceIds.joinToString()} could not be terminated ($error)"
    }

    /** The cleanup itself threw, so nothing is known about the builders. */
    data class CleanupFailed(
        val error: String,
    ) : Unresolved {
        override val reason: String get() = "its cleanup failed ($error)"
    }
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
     * After each failed attempt, [cleanUp] deals with its builder first; then [report] is told
     * whether another attempt follows. One follows only for a dropped session within the limit whose
     * builder is gone, so a second builder never runs beside one that may still be up.
     *
     * @param build runs one attempt, given its 1-based number
     * @param cleanUp tears down (or keeps) the failed attempt's builder; `retryWanted` says whether
     *   the build would run again, in which case the builder must not be kept
     * @param report reports the failed attempt with whether another attempt follows and what
     *   became of its builder
     */
    fun run(
        build: (attempt: Int) -> PackerBuildOutcome,
        cleanUp: (attempt: Int, failed: PackerBuildOutcome.Failed, retryWanted: Boolean) -> BuilderCleanup,
        report: (attempt: Int, failed: PackerBuildOutcome.Failed, willRetry: Boolean, cleanup: BuilderCleanup) -> Unit,
    ): PackerBuildOutcome {
        var attempt = 0
        var retryNext = false
        val config =
            RetryConfig
                .custom<PackerBuildOutcome>()
                .maxAttempts(maxAttempts)
                .intervalFunction { _ -> delay.toMillis() }
                .retryOnResult { retryNext }
                // Only a dropped session runs again: a throw from an attempt or its report is final.
                .retryOnException { false }
                .build()
        return Retry
            .of("packer-build", config)
            .executeSupplier {
                attempt++
                build(attempt).also { outcome ->
                    retryNext =
                        when (outcome) {
                            is PackerBuildOutcome.Succeeded -> false
                            is PackerBuildOutcome.Failed -> afterFailure(attempt, outcome, cleanUp, report)
                        }
                }
            }
    }

    private fun afterFailure(
        attempt: Int,
        failed: PackerBuildOutcome.Failed,
        cleanUp: (Int, PackerBuildOutcome.Failed, Boolean) -> BuilderCleanup,
        report: (Int, PackerBuildOutcome.Failed, Boolean, BuilderCleanup) -> Unit,
    ): Boolean {
        val retryWanted = failed.failure is PackerFailure.SessionDropped && attempt < maxAttempts
        // A cleanup that throws still leaves the attempt reported, with its reason and log path.
        val cleanup =
            runCatchingException { cleanUp(attempt, failed, retryWanted) }
                .getOrElse { BuilderCleanup.CleanupFailed(it.messageOrString()) }
        val willRetry = retryWanted && cleanup !is BuilderCleanup.Unresolved
        report(attempt, failed, willRetry, cleanup)
        return willRetry
    }
}
