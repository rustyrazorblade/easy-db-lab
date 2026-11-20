package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkException

/**
 * How stopping a data bucket's request metrics ended.
 */
sealed interface MetricsStop {
    /** The cluster has no data bucket recorded. */
    data object NoBucket : MetricsStop

    /** The bucket's request metrics are off. */
    data class Stopped(
        val bucket: String,
    ) : MetricsStop

    /** S3 refused; the failure was reported and the bucket is as it was. */
    data class Failed(
        val bucket: String,
        val reason: String,
    ) : MetricsStop
}

/**
 * What `down --all` did with the data buckets it found.
 */
data class DataBucketSweep(
    val deleted: List<String>,
    val kept: List<String>,
)

/**
 * The data-bucket side of `down`. The bucket and its objects are the owner's data, so nothing here
 * expires or deletes them: a cluster's teardown only stops the bucket's request metrics, and
 * `down --all` deletes a data bucket only when it is empty, which S3 itself enforces.
 */
class DataBucketTeardown(
    private val s3BucketService: AwsS3BucketService,
    private val eventBus: EventBus,
) {
    private val log = KotlinLogging.logger {}

    /** Disables request metrics on the cluster's data bucket. Sets no expiry. */
    fun stopMetrics(clusterState: ClusterState): MetricsStop {
        val dataBucket = clusterState.dataBucket
        if (dataBucket.isBlank()) {
            log.debug { "No data bucket configured, skipping teardown" }
            return MetricsStop.NoBucket
        }

        return try {
            s3BucketService.teardownDataBucket(dataBucket, clusterState.metricsConfigId())
            MetricsStop.Stopped(dataBucket)
        } catch (e: SdkException) {
            log.warn(e) { "Failed to tear down data bucket: $dataBucket" }
            val reason = e.messageOrString()
            eventBus.emit(Event.Teardown.DataBucketMetricsDisableFailed(dataBucket, reason))
            MetricsStop.Failed(dataBucket, reason)
        }
    }

    /**
     * Finds every data bucket and deletes each one that is empty. A bucket that still holds objects
     * is kept as it is, with no expiry rule. Called during `--all` teardown.
     */
    fun deleteEmptyBuckets(): DataBucketSweep {
        val dataBuckets =
            try {
                s3BucketService.findDataBuckets()
            } catch (e: SdkException) {
                log.warn(e) { "Failed to discover data buckets" }
                eventBus.emit(Event.Teardown.DataBucketDiscoveryFailed(e.messageOrString()))
                return DataBucketSweep(emptyList(), emptyList())
            }
        if (dataBuckets.isEmpty()) {
            log.debug { "No data buckets found" }
        }

        val (deleted, kept) = dataBuckets.partition(::deleteIfEmpty)
        return DataBucketSweep(deleted, kept)
    }

    private fun deleteIfEmpty(bucket: String): Boolean =
        try {
            eventBus.emit(Event.S3.DataBucketDeleting(bucket))
            s3BucketService
                .deleteEmptyBucket(bucket)
                .onSuccess { eventBus.emit(Event.S3.DataBucketDeleted(bucket)) }
                .onFailure { exception ->
                    eventBus.emit(Event.S3.DataBucketKept(bucket, exception.messageOrString()))
                }.isSuccess
        } catch (e: SdkException) {
            log.warn(e) { "Failed to handle data bucket: $bucket" }
            eventBus.emit(Event.S3.DataBucketKept(bucket, e.messageOrString()))
            false
        }
}
