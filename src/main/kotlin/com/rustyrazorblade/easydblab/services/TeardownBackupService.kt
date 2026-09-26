package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import java.time.Clock

/**
 * Saves everything the cluster holds that is not yet in S3 before it is torn down: the annotation
 * mirror, the verified Loki and Mimir flushes, and the annotations backup ([TeardownFlushService]).
 *
 * The flush runs once. A failure is returned as it stopped and never retried: a retry would POST to
 * an ingester the failed attempt already stopped. A flush that succeeds is recorded in the cluster
 * state ([ClusterState.tailFlush]), so a `down` re-run after a failed teardown skips it.
 */
interface TeardownBackupService {
    /**
     * Saves the cluster's tail before teardown and records it.
     *
     * @param controlHost The control node running Mimir, Loki and Grafana.
     * @param clusterState The cluster state carrying the account bucket and tenant; the record is
     *   set on it and saved.
     * @return success once everything is proven in S3 and recorded, or a [FlushStepFailed].
     */
    fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<Unit>
}

/**
 * [TeardownBackupService] that runs one [TeardownFlushService] attempt and records its success.
 */
class DefaultTeardownBackupService(
    private val flushService: TeardownFlushService,
    private val clusterStateManager: ClusterStateManager,
    private val clock: Clock = Clock.systemUTC(),
) : TeardownBackupService {
    override fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<Unit> =
        flushService.saveTail(controlHost, clusterState).mapCatching { report ->
            runCatching {
                clusterState.tailFlush =
                    TailFlushRecord(
                        completedAt = clock.instant(),
                        lokiIndexFiles = report.lokiIndexFiles,
                        lokiChunksFlushed = report.lokiChunksFlushed,
                        mimirBlocks = report.mimirBlocks,
                    )
                clusterStateManager.save(clusterState)
            }.getOrElse { failure ->
                // The flush left both backends at 0, so an unrecorded flush cannot be run again.
                val stopped = listOf(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.MIMIR_APP_LABEL)
                throw FlushStepFailed(FlushStep.RECORD, stopped.associateWith { BackendState.SCALED_TO_ZERO }, failure)
            }
        }
}
