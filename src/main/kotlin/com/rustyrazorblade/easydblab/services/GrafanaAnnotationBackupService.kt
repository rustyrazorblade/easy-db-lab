package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.time.Instant

/**
 * Result of a Grafana annotations backup.
 *
 * @property s3Path The account-level S3 path where the annotations JSON was written.
 * @property annotationCount The number of annotations captured.
 */
data class GrafanaAnnotationBackupResult(
    val s3Path: ClusterS3Path,
    val annotationCount: Int,
)

/**
 * Service that backs up the Grafana annotations of a running cluster to an account-level S3 location.
 *
 * The annotations are the A/B config-change markers an operator wants to keep after the ephemeral
 * cluster is gone. This service captures them over the Grafana HTTP API (`GET /api/annotations`, via
 * [GrafanaDashboardService.fetchAnnotations]) and uploads the JSON verbatim to a path OUTSIDE the
 * per-cluster prefix that teardown expires, so a backup is never lost to cluster teardown.
 */
interface GrafanaAnnotationBackupService {
    /**
     * Captures the cluster's Grafana annotations and uploads them to the account-level S3 location.
     *
     * @param controlHost The control node running Grafana.
     * @param clusterState The cluster state carrying the account-level S3 bucket and cluster name.
     * @return A [GrafanaAnnotationBackupResult] on success, or a failure Result on any error.
     */
    fun backup(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<GrafanaAnnotationBackupResult>
}

/**
 * Default implementation of [GrafanaAnnotationBackupService].
 *
 * @property grafanaDashboardService Reaches the Grafana HTTP API over the proxied client.
 * @property objectStore Uploads the JSON artifact to S3.
 * @property eventBus Emits backup lifecycle events.
 */
class DefaultGrafanaAnnotationBackupService(
    private val grafanaDashboardService: GrafanaDashboardService,
    private val objectStore: ObjectStore,
    private val eventBus: EventBus,
) : GrafanaAnnotationBackupService {
    override fun backup(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<GrafanaAnnotationBackupResult> =
        runCatching {
            val bucket =
                clusterState.s3Bucket
                    ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")

            val artifact =
                ClusterS3Path.grafanaAnnotationsArtifact(
                    accountBucket = bucket,
                    clusterName = clusterState.name,
                    timestampMillis = Instant.now().toEpochMilli(),
                )

            eventBus.emit(Event.Backup.GrafanaAnnotationsBackupStarting(artifact.toUri()))

            val annotationsJson = grafanaDashboardService.fetchAnnotations(controlHost)
            val count = countAnnotations(annotationsJson)
            objectStore.uploadContent(annotationsJson, artifact)

            eventBus.emit(Event.Backup.GrafanaAnnotationsBackupComplete(artifact.toUri(), count))
            GrafanaAnnotationBackupResult(s3Path = artifact, annotationCount = count)
        }

    /**
     * Counts the annotations in Grafana's JSON response. Grafana returns a JSON array; a non-array
     * body (unexpected) counts as zero rather than failing the backup, since the artifact is still
     * uploaded verbatim.
     */
    private fun countAnnotations(json: String): Int = runCatching { Json.parseToJsonElement(json).jsonArray.size }.getOrDefault(0)
}
