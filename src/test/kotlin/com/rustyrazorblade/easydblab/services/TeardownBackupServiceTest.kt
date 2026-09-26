package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `down` saves the tail with one flush attempt and never retries it: a retry would POST to an
 * ingester the failed attempt already stopped. A flush that succeeds is recorded in the cluster
 * state, so a `down` re-run after a failed teardown skips it.
 */
class TeardownBackupServiceTest {
    @TempDir
    lateinit var dir: File

    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state = ClusterState(name = "lab", versions = mutableMapOf())
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Answers each attempt from [outcomes] in turn, and counts the attempts. */
    private class ScriptedFlush(
        outcomes: List<Result<FlushReport>>,
    ) : TeardownFlushService {
        private val pending = ArrayDeque(outcomes)
        var attempts = 0

        override fun saveTail(
            controlHost: ClusterHost,
            clusterState: ClusterState,
        ): Result<FlushReport> {
            attempts++
            return pending.removeFirst()
        }
    }

    private val report = FlushReport(lokiIndexFiles = 1, lokiChunksFlushed = 3, mimirBlocks = 2)

    private val failed =
        Result.failure<FlushReport>(
            FlushStepFailed(FlushStep.LOKI_SHUTDOWN, mapOf("loki" to BackendState.INGESTER_STOPPED), IllegalStateException("timed out")),
        )

    private fun manager(file: File = File(dir, "state.json")) = ClusterStateManager(file)

    @Test
    fun `a flush that succeeds is recorded in the cluster state with what it verified`() {
        val manager = manager()
        val flush = ScriptedFlush(listOf(Result.success(report)))

        DefaultTeardownBackupService(flush, manager, clock).backupBeforeTeardown(control, state).getOrThrow()

        val expected = TailFlushRecord(completedAt = now, lokiIndexFiles = 1, lokiChunksFlushed = 3, mimirBlocks = 2)
        assertThat(state.tailFlush).isEqualTo(expected)
        assertThat(manager.load().tailFlush).isEqualTo(expected)
    }

    @Test
    fun `a failed flush is not retried, is returned as it failed, and records nothing`() {
        val manager = manager()
        val flush = ScriptedFlush(listOf(failed, Result.success(report)))

        val result = DefaultTeardownBackupService(flush, manager, clock).backupBeforeTeardown(control, state)

        assertThat(flush.attempts).isEqualTo(1)
        assertThat(result.exceptionOrNull()).isSameAs(failed.exceptionOrNull())
        assertThat(state.tailFlush).isNull()
        assertThat(manager.exists()).isFalse()
    }

    @Test
    fun `a flush whose record cannot be saved fails at the record step, with both backends at 0`() {
        // A directory where the state file should be: the save fails.
        val unwritable = File(dir, "state.json").apply { mkdirs() }

        val result =
            DefaultTeardownBackupService(ScriptedFlush(listOf(Result.success(report))), manager(unwritable), clock)
                .backupBeforeTeardown(control, state)

        val failure = result.exceptionOrNull() as FlushStepFailed
        assertThat(failure.step).isEqualTo(FlushStep.RECORD)
        assertThat(failure.backends).containsExactly(
            entry("loki", BackendState.SCALED_TO_ZERO),
            entry("mimir", BackendState.SCALED_TO_ZERO),
        )
    }
}
