package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.SavedSignal
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import java.time.Clock
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Saves everything the cluster holds that is not yet in S3 before it is torn down, and keeps the
 * record of what is saved ([ClusterState.tailFlush]).
 *
 * The save runs once and is never retried within a run: a retry would POST to an ingester the
 * failed attempt already stopped. Logs and metrics are recorded the moment each flush succeeds, so
 * a `down` re-run after a failed or interrupted save skips them and saves the rest again.
 */
interface TeardownBackupService {
    /**
     * Saves every signal the cluster state does not record as saved.
     *
     * @param controlHost The control node running the backends and Grafana.
     * @param clusterState The cluster state carrying the account bucket and tenant; the record is
     *   set on it and saved.
     * @return what was saved, or a [TailFlushFailed] naming every signal that is not.
     */
    fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<FlushOutcome>

    /**
     * The signals a teardown now would lose: logs and metrics unless recorded, then traces and
     * annotations. Profiles are never listed: Pyroscope writes each batch to S3 before it accepts it.
     */
    fun unsavedSignals(clusterState: ClusterState): List<TailSignal>
}

/**
 * The save left [failures] unsaved. Nothing was undone: each workload is as [backends] says.
 *
 * @property failures each signal that is not saved, with the step it stopped in.
 * @property backends each workload's state once every step finished.
 */
class TailFlushFailed(
    val failures: Map<TailSignal, FlushStepFailed>,
    val backends: Map<String, BackendState>,
) : RuntimeException(
        "The pre-teardown save failed for " +
            failures.entries.joinToString("; ") { (signal, failure) -> "${signal.description}: ${failure.message}" },
    )

/**
 * [TeardownBackupService] that runs one [TeardownFlushService] save and records logs and metrics
 * through a single writer thread, the only thread that writes the state during the save.
 */
class DefaultTeardownBackupService(
    private val flushService: TeardownFlushService,
    private val clusterStateManager: ClusterStateManager,
    private val clock: Clock = Clock.systemUTC(),
) : TeardownBackupService {
    override fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<FlushOutcome> {
        val pending = TailSignal.entries.toSet() - recorded(clusterState).keys
        val outcome =
            Executors.newSingleThreadExecutor().use { writer ->
                flushService.saveTail(controlHost, clusterState, pending) { signal, report ->
                    // Waits for the write, so a signal counts as saved only once its record is on disk.
                    try {
                        writer.submit { record(clusterState, signal, report) }.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
            }
        return if (outcome.failed.isEmpty()) {
            Result.success(outcome)
        } else {
            Result.failure(TailFlushFailed(outcome.failed, outcome.backends))
        }
    }

    override fun unsavedSignals(clusterState: ClusterState): List<TailSignal> =
        (TailSignal.entries - TailSignal.PROFILES).filterNot { it in recorded(clusterState) }

    private fun recorded(clusterState: ClusterState): Map<TailSignal, SavedSignal> = clusterState.tailFlush?.signals.orEmpty()

    private fun record(
        clusterState: ClusterState,
        signal: TailSignal,
        report: SignalReport,
    ) {
        clusterState.tailFlush =
            TailFlushRecord(recorded(clusterState) + (signal to SavedSignal(clock.instant(), report.verifiedObjects)))
        clusterStateManager.save(clusterState)
    }
}
