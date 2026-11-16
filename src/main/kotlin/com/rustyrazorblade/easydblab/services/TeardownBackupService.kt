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
 * a `down` re-run after a failed or interrupted save skips them and saves the rest again. A save of
 * every signal is recorded as complete ([TailFlushRecord.saveCompletedAt]), so a re-run after a
 * teardown that failed part-way saves nothing again.
 */
interface TeardownBackupService {
    /**
     * Saves every signal the cluster state does not record as saved.
     *
     * @param controlHost The control node running the backends and Grafana.
     * @param clusterState The cluster state carrying the account bucket and tenant; the record is
     *   set on it and saved.
     * @return what was saved, recorded as a complete save; or a [TailFlushFailed] naming every signal
     *   that is not saved; or the failure to write the record.
     */
    fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<FlushOutcome>

    /**
     * The signals a teardown now would lose: logs and metrics unless recorded, then traces and
     * annotations; nothing once a save of every signal is recorded as complete. Profiles are never
     * listed: Pyroscope writes each batch to S3 before it accepts it.
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
                flushService.saveTail(controlHost, clusterState, pending) { signal, _ ->
                    // Waits for the write, so a signal counts as saved only once its record is on disk.
                    try {
                        writer.submit { record(clusterState, signal) }.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
            }
        if (outcome.failed.isNotEmpty()) return Result.failure(TailFlushFailed(outcome.failed, outcome.backends))
        return runCatching {
            recordComplete(clusterState)
            outcome
        }
    }

    override fun unsavedSignals(clusterState: ClusterState): List<TailSignal> =
        if (clusterState.tailFlush?.saveCompletedAt != null) {
            emptyList()
        } else {
            (TailSignal.entries - TailSignal.PROFILES).filterNot { it in recorded(clusterState) }
        }

    private fun recorded(clusterState: ClusterState): Map<TailSignal, SavedSignal> = clusterState.tailFlush?.signals.orEmpty()

    private fun record(
        clusterState: ClusterState,
        signal: TailSignal,
    ) {
        clusterState.tailFlush = TailFlushRecord(recorded(clusterState) + (signal to SavedSignal(clock.instant())))
        clusterStateManager.save(clusterState)
    }

    /**
     * Records that every signal is saved. `down` starts the infrastructure teardown right after, so a
     * re-run finds the save complete and goes straight to the teardown.
     */
    private fun recordComplete(clusterState: ClusterState) {
        clusterState.tailFlush = TailFlushRecord(recorded(clusterState), saveCompletedAt = clock.instant())
        clusterStateManager.save(clusterState)
    }
}
