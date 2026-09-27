package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.SavedSignal
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What `down` records of a save, and when. Logs and metrics are recorded the moment each flush
 * succeeds, through one writer, so an interrupted `down` keeps them; a re-run skips them and saves
 * the rest again. The save itself is a hand-written fake; the state file is real.
 */
class TeardownBackupServiceTest {
    @TempDir
    lateinit var dir: File

    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val state = ClusterState(name = "lab", versions = mutableMapOf())

    private fun manager() = ClusterStateManager(File(dir, "state.json"))

    /** Saves the pending signals it is given, calling back for each in [saves] order. */
    private open inner class FakeSave(
        private val failing: Set<TailSignal> = emptySet(),
    ) : TeardownFlushService {
        var pending: Set<TailSignal> = emptySet()

        open fun beforeReport(
            signal: TailSignal,
            onSaved: (TailSignal, SignalReport) -> Unit,
        ) = Unit

        override fun saveTail(
            controlHost: ClusterHost,
            clusterState: ClusterState,
            pending: Set<TailSignal>,
            onSaved: (TailSignal, SignalReport) -> Unit,
        ): FlushOutcome {
            this.pending = pending
            val saved = pending - failing
            saved.filter { it in TailSignal.RECORDED }.forEach { signal ->
                beforeReport(signal, onSaved)
                onSaved(signal, if (signal == TailSignal.LOGS) SignalReport.Logs(2, 9) else SignalReport.Metrics(5))
            }
            return FlushOutcome(
                saved = saved.associateWith { SignalReport.Profiles },
                failed =
                    failing.associateWith {
                        FlushStepFailed(
                            FlushStep.TEMPO_LIVE_TRACES,
                            mapOf("tempo" to BackendState.RUNNING),
                            IllegalStateException("timed out"),
                        )
                    },
                backends = mapOf("loki" to BackendState.SCALED_TO_ZERO),
            )
        }
    }

    @Test
    fun `logs and metrics are recorded with what they verified, and nothing else is`() {
        val manager = manager()

        DefaultTeardownBackupService(FakeSave(), manager, clock).backupBeforeTeardown(control, state).getOrThrow()

        val expected =
            TailFlushRecord(
                mapOf(
                    TailSignal.LOGS to SavedSignal(now, verifiedObjects = 2),
                    TailSignal.METRICS to SavedSignal(now, verifiedObjects = 5),
                ),
            )
        assertThat(state.tailFlush).isEqualTo(expected)
        assertThat(manager.load().tailFlush).isEqualTo(expected)
    }

    /** The logs record must be on disk while the Mimir flush still runs, so an interrupt keeps it. */
    @Test
    fun `logs are on disk while the metrics flush is still running`() {
        val manager = manager()
        val logsOnDisk = CountDownLatch(1)
        val save =
            object : FakeSave() {
                override fun beforeReport(
                    signal: TailSignal,
                    onSaved: (TailSignal, SignalReport) -> Unit,
                ) {
                    if (signal == TailSignal.METRICS) {
                        // The metrics flush is still running here; the logs record must already be written.
                        assertThat(manager.load().tailFlush?.signals).containsOnlyKeys(TailSignal.LOGS)
                        logsOnDisk.countDown()
                    }
                }
            }

        DefaultTeardownBackupService(save, manager, clock).backupBeforeTeardown(control, state).getOrThrow()

        assertThat(logsOnDisk.await(0, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a re-run's pending set leaves out the recorded signals`() {
        state.tailFlush = TailFlushRecord(mapOf(TailSignal.LOGS to SavedSignal(now, 1)))
        val save = FakeSave()

        DefaultTeardownBackupService(save, manager(), clock).backupBeforeTeardown(control, state).getOrThrow()

        assertThat(save.pending).containsExactlyInAnyOrder(
            TailSignal.METRICS,
            TailSignal.TRACES,
            TailSignal.PROFILES,
            TailSignal.ANNOTATIONS,
        )
    }

    @Test
    fun `a failed signal fails the save with every failure, and the signals that were saved stay recorded`() {
        val manager = manager()

        val failure =
            DefaultTeardownBackupService(FakeSave(failing = setOf(TailSignal.TRACES)), manager, clock)
                .backupBeforeTeardown(control, state)
                .exceptionOrNull() as TailFlushFailed

        assertThat(failure.failures.keys).containsExactly(TailSignal.TRACES)
        assertThat(failure).hasMessageContaining("traces (Tempo)").hasMessageContaining("timed out")
        assertThat(failure.backends).containsEntry("loki", BackendState.SCALED_TO_ZERO)
        assertThat(manager.load().tailFlush?.signals).containsOnlyKeys(TailSignal.LOGS, TailSignal.METRICS)
    }

    @Test
    fun `nothing recorded leaves logs, metrics, traces and annotations unsaved, never profiles`() {
        val service = DefaultTeardownBackupService(FakeSave(), manager(), clock)

        assertThat(service.unsavedSignals(state))
            .containsExactly(TailSignal.LOGS, TailSignal.METRICS, TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `recorded logs leave metrics, traces and annotations unsaved`() {
        state.tailFlush = TailFlushRecord(mapOf(TailSignal.LOGS to SavedSignal(now, 1)))

        assertThat(DefaultTeardownBackupService(FakeSave(), manager(), clock).unsavedSignals(state))
            .containsExactly(TailSignal.METRICS, TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `recorded logs and metrics leave traces and annotations unsaved`() {
        state.tailFlush = TailFlushRecord(mapOf(TailSignal.LOGS to SavedSignal(now, 1), TailSignal.METRICS to SavedSignal(now, 2)))

        assertThat(DefaultTeardownBackupService(FakeSave(), manager(), clock).unsavedSignals(state))
            .containsExactly(TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }
}
