package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.SnapshotName
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.time.Clock

/**
 * Result of a Grafana annotations backup.
 *
 * @property s3Path Where the annotations JSON was written, in the tenant's annotations directory.
 * @property annotationCount The number of annotations captured.
 */
data class GrafanaAnnotationBackupResult(
    val s3Path: ClusterS3Path,
    val annotationCount: Int,
)

/**
 * Service that backs up the Grafana annotations of a running cluster to the observability store.
 *
 * The annotations are the A/B config-change markers an operator wants to keep after the ephemeral
 * cluster is gone. This service captures them over the Grafana HTTP API (`GET /api/annotations`, via
 * [GrafanaDashboardService.fetchAnnotations]) and uploads the JSON verbatim to
 * `observability/annotations/<tenant>/<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, so clusters in one
 * tenant never overwrite each other's backups.
 */
interface GrafanaAnnotationBackupService {
    /**
     * Captures the cluster's Grafana annotations and uploads them to the tenant's annotations directory.
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
 * @property clock The time a backup is named by.
 */
class DefaultGrafanaAnnotationBackupService(
    private val grafanaDashboardService: GrafanaDashboardService,
    private val objectStore: ObjectStore,
    private val eventBus: EventBus,
    private val clock: Clock = Clock.systemUTC(),
) : GrafanaAnnotationBackupService {
    override fun backup(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<GrafanaAnnotationBackupResult> =
        runCatching {
            val store = ObservabilityStore.from(clusterState)
            // Never overwrite an earlier backup that took the same second.
            val name = SnapshotName.firstFree(clusterState, clock.instant()) { objectStore.fileExists(store.annotationsArtifact(it)) }
            val artifact = store.annotationsArtifact(name)

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
