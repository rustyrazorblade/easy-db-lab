package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.messageOrString
import java.io.File

/** The fixed facts of one AMI build that every attempt shares. */
data class PackerBuild(
    val template: String,
    val buildId: String,
    val region: String,
    val maxAttempts: Int,
    val keepOnError: Boolean,
)

/**
 * The pure halves of one `packer build` attempt: what its container run means, and how a failed
 * attempt is reported. Kept apart from [Packer], which runs the container and talks to the cloud,
 * so both decisions are tested without Docker or AWS.
 */
object PackerAttempt {
    /**
     * The outcome of an attempt from its container [run] (the outer result fails when the container
     * could not run, or outlived its bound; the inner one when Packer exited non-zero) and its
     * debug [logFile]. Only a non-zero Packer exit is read from the log; a container failure is a
     * build error, never retried, because no session was lost.
     */
    fun outcome(
        run: Result<Result<String>>,
        logFile: File,
    ): PackerBuildOutcome =
        run.fold(
            onSuccess = { exit ->
                when {
                    exit.isSuccess -> PackerBuildOutcome.Succeeded
                    else -> PackerBuildOutcome.Failed(PackerFailure.fromLog(readLog(logFile)), logFile)
                }
            },
            onFailure = { error -> PackerBuildOutcome.Failed(PackerFailure.BuildError(error.messageOrString()), logFile) },
        )

    /** The event for a failed [attempt] of [build]. */
    fun failedEvent(
        build: PackerBuild,
        attempt: Int,
        failed: PackerBuildOutcome.Failed,
        willRetry: Boolean,
        cleanup: BuilderCleanup,
    ): Event.Ami.BuildAttemptFailed {
        val builders = builderFacts(cleanup)
        return Event.Ami.BuildAttemptFailed(
            template = build.template,
            buildId = build.buildId,
            region = build.region,
            attempt = attempt,
            maxAttempts = build.maxAttempts,
            sessionDropped = failed.failure is PackerFailure.SessionDropped,
            willRetry = willRetry,
            reason = failed.failure.reason,
            // The log file is created before the container starts; empty means Packer never ran.
            logPath = failed.logFile.takeIf { it.length() > 0 }?.absolutePath,
            builderState = builders.state,
            builderInstanceIds = builders.ids,
            builderError = (cleanup as? BuilderCleanup.Unresolved)?.error,
            keepOnError = build.keepOnError,
        )
    }

    private data class BuilderFacts(
        val state: Event.Ami.BuilderState,
        val ids: List<String>,
    )

    private fun builderFacts(cleanup: BuilderCleanup): BuilderFacts =
        when (cleanup) {
            is BuilderCleanup.NotFound -> BuilderFacts(Event.Ami.BuilderState.NOT_FOUND, emptyList())
            is BuilderCleanup.Terminated -> BuilderFacts(Event.Ami.BuilderState.TERMINATED, cleanup.instanceIds)
            is BuilderCleanup.Kept -> BuilderFacts(Event.Ami.BuilderState.KEPT, cleanup.instanceIds)
            is BuilderCleanup.LookupFailed -> BuilderFacts(Event.Ami.BuilderState.LOOKUP_FAILED, emptyList())
            is BuilderCleanup.TerminateFailed -> BuilderFacts(Event.Ami.BuilderState.TERMINATE_FAILED, cleanup.instanceIds)
            is BuilderCleanup.CleanupFailed -> BuilderFacts(Event.Ami.BuilderState.CLEANUP_FAILED, emptyList())
        }

    private fun readLog(logFile: File): String = if (logFile.exists()) logFile.readText() else ""
}
