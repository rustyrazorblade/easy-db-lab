package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.events.Event.Ami.BuilderState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

/**
 * Tests for [PackerAttempt]: a container that could not run is a build error (never retried, since
 * no session was lost), a non-zero Packer exit is classified from its log, and a failed attempt's
 * event carries the builder state, ids and error from its cleanup and a log path only when Packer
 * wrote one.
 */
class PackerAttemptTest {
    @TempDir
    lateinit var dir: File

    private val build = PackerBuild("base.pkr.hcl", "0123456789ab", "eu-west-1", maxAttempts = 3, keepOnError = true)

    private fun log(text: String?): File = File(dir, "packer.log").also { file -> text?.let { file.writeText(it) } }

    @Test
    fun `a zero exit succeeds`() {
        assertThat(PackerAttempt.outcome(Result.success(Result.success("done")), log(null))).isEqualTo(PackerBuildOutcome.Succeeded)
    }

    @Test
    fun `a container that could not run is a build error even when the log shows a dropped session`() {
        val logFile = log(DROPPED_LOG)

        val outcome = PackerAttempt.outcome(Result.failure(IllegalStateException("container timed out")), logFile)

        assertThat(outcome).isEqualTo(PackerBuildOutcome.Failed(PackerFailure.BuildError("container timed out"), logFile))
    }

    @Test
    fun `a non-zero exit is classified from the log`() {
        val logFile = log(DROPPED_LOG)

        val outcome = PackerAttempt.outcome(Result.success(Result.failure(IllegalStateException("exit 1"))), logFile)

        assertThat(outcome).isEqualTo(PackerBuildOutcome.Failed(PackerFailure.fromLog(DROPPED_LOG), logFile))
        assertThat((outcome as PackerBuildOutcome.Failed).failure).isInstanceOf(PackerFailure.SessionDropped::class.java)
    }

    @Test
    fun `a non-zero exit with no log is classified as if the log were empty`() {
        val logFile = File(dir, "missing/packer.log")

        val outcome = PackerAttempt.outcome(Result.success(Result.failure(IllegalStateException("exit 1"))), logFile)

        assertThat(outcome).isEqualTo(PackerBuildOutcome.Failed(PackerFailure.fromLog(""), logFile))
    }

    /** One cleanup and the builder facts its event must carry. */
    data class CleanupCase(
        val cleanup: BuilderCleanup,
        val state: BuilderState,
        val ids: List<String>,
        val error: String?,
    ) {
        override fun toString(): String = cleanup.toString()
    }

    companion object {
        private const val DROPPED_LOG =
            "2026/11/21 04:38:46 ui error: Build 'amazon-ebs.ubuntu' errored after 3 minutes: Upload failed: EOF"
        private val ids = listOf("i-1", "i-2")

        @JvmStatic
        fun cleanups(): Stream<CleanupCase> =
            Stream.of(
                CleanupCase(BuilderCleanup.NotFound, BuilderState.NOT_FOUND, emptyList(), null),
                CleanupCase(BuilderCleanup.Terminated(ids), BuilderState.TERMINATED, ids, null),
                CleanupCase(BuilderCleanup.Kept(ids), BuilderState.KEPT, ids, null),
                CleanupCase(BuilderCleanup.LookupFailed("throttled"), BuilderState.LOOKUP_FAILED, emptyList(), "throttled"),
                CleanupCase(BuilderCleanup.TerminateFailed(ids, "denied"), BuilderState.TERMINATE_FAILED, ids, "denied"),
                CleanupCase(BuilderCleanup.CleanupFailed("boom"), BuilderState.CLEANUP_FAILED, emptyList(), "boom"),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanups")
    fun `the event carries what became of the builders`(case: CleanupCase) {
        val failed = PackerBuildOutcome.Failed(PackerFailure.SessionDropped("EOF"), log(DROPPED_LOG))

        val event = PackerAttempt.failedEvent(build, attempt = 2, failed, willRetry = false, case.cleanup)

        assertThat(event.builderState).isEqualTo(case.state)
        assertThat(event.builderInstanceIds).isEqualTo(case.ids)
        assertThat(event.builderError).isEqualTo(case.error)
        assertThat(event.sessionDropped).isTrue()
        assertThat(event.buildId).isEqualTo("0123456789ab")
        assertThat(event.region).isEqualTo("eu-west-1")
        assertThat(event.keepOnError).isTrue()
        assertThat(event.logPath).isEqualTo(failed.logFile.absolutePath)
    }

    @Test
    fun `an attempt whose log is empty or missing reports no log path`() {
        val empty = PackerBuildOutcome.Failed(PackerFailure.BuildError("no docker"), log(""))
        val missing = PackerBuildOutcome.Failed(PackerFailure.BuildError("no docker"), File(dir, "missing/packer.log"))

        assertThat(listOf(empty, missing)).allSatisfy { failed ->
            val event = PackerAttempt.failedEvent(build, 1, failed, willRetry = false, BuilderCleanup.NotFound)
            assertThat(event.logPath).isNull()
            assertThat(event.sessionDropped).isFalse()
        }
    }
}
