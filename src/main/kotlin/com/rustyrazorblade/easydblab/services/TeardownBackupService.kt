package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry

/**
 * Runs the metrics backup and the Grafana annotations backup as one coupled operation before a
 * cluster is torn down.
 *
 * The two backups are always attempted together, never one without the other, so a teardown never
 * keeps metrics while silently dropping annotations (or the reverse). Each backup is retried with
 * resilience4j to ride out a transient failure. If either backup still fails after its retries, the
 * whole operation fails; `down` then aborts and removes no infrastructure. See design decisions
 * D3 and D4 in `openspec/changes/issue-939`.
 */
interface TeardownBackupService {
    /**
     * Backs up the cluster's metrics and Grafana annotations before teardown.
     *
     * @param controlHost The control node running VictoriaMetrics and Grafana.
     * @param clusterState The cluster state carrying the S3 destinations.
     * @return A success Result when both backups succeed, or a failure Result aggregating the
     *   failures when either backup fails after its retries.
     */
    fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<Unit>
}

/**
 * Default implementation of [TeardownBackupService].
 *
 * @property victoriaBackupService Backs up VictoriaMetrics data. On the teardown path this is wired
 *   with a short Job timeout so a stuck backup does not delay the abort/`--force` decision.
 * @property annotationBackupService Backs up the Grafana annotations to the account-level location.
 */
class DefaultTeardownBackupService(
    private val victoriaBackupService: VictoriaBackupService,
    private val annotationBackupService: GrafanaAnnotationBackupService,
) : TeardownBackupService {
    private val log = KotlinLogging.logger {}

    override fun backupBeforeTeardown(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<Unit> {
        // Both backups are always attempted; neither short-circuits the other. Each result is
        // captured so a failure in the first does not skip the second.
        val metricsResult =
            withRetry("teardown-metrics-backup") {
                victoriaBackupService.backupMetrics(controlHost, clusterState).getOrThrow()
            }
        val annotationsResult =
            withRetry("teardown-annotations-backup") {
                annotationBackupService.backup(controlHost, clusterState).getOrThrow()
            }

        val failures = listOfNotNull(metricsResult.exceptionOrNull(), annotationsResult.exceptionOrNull())
        if (failures.isEmpty()) {
            return Result.success(Unit)
        }

        val combined =
            IllegalStateException(
                "Pre-teardown backup failed: " +
                    failures.joinToString("; ") { it.message ?: it.toString() },
            )
        failures.forEach(combined::addSuppressed)
        return Result.failure(combined)
    }

    /**
     * Runs [operation] under a resilience4j retry so a transient backup failure is retried before it
     * is treated as fatal. Wraps the outcome in a [Result] so the caller can attempt both backups.
     */
    private fun <T> withRetry(
        name: String,
        operation: () -> T,
    ): Result<T> {
        val retry = Retry.of(name, RetryUtil.createNetworkRetryConfig<T>())
        return runCatching { Retry.decorateSupplier(retry, operation).get() }
            .onFailure { log.warn(it) { "$name failed after retries" } }
    }
}
