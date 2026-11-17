package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Bucket
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.ListBucketsResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag

/**
 * [DataBucketTeardown] over the real [AwsS3BucketService] with a mocked S3 client. A cluster's
 * teardown only stops the data bucket's request metrics; `--all` deletes a data bucket only when
 * S3 accepts the delete, which it does only for an empty bucket.
 */
class DataBucketTeardownTest {
    private val s3: S3Client = mock()
    private val emitted = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }
    private val teardown = DataBucketTeardown(AwsS3BucketService(s3, mock<AccountIdentity>()), eventBus)

    @BeforeEach
    fun clearEvents() = emitted.clear()

    private fun cluster(dataBucket: String) = ClusterState(name = "lab", clusterId = "abc", dataBucket = dataBucket)

    private fun listBuckets(vararg names: String) {
        whenever(
            s3.listBuckets(),
        ).thenReturn(ListBucketsResponse.builder().buckets(names.map { Bucket.builder().name(it).build() }).build())
        val tagged =
            GetBucketTaggingResponse
                .builder()
                .tagSet(
                    Tag
                        .builder()
                        .key(Constants.Vpc.TAG_KEY)
                        .value(Constants.Vpc.TAG_VALUE)
                        .build(),
                ).build()
        whenever(s3.getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(tagged)
    }

    @Test
    fun `a cluster with no data bucket stops nothing`() {
        assertThat(teardown.stopMetrics(cluster(""))).isEqualTo(MetricsStop.NoBucket)

        verify(s3, never()).deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>())
    }

    @Test
    fun `stopping metrics deletes the cluster's metrics configuration and nothing else`() {
        val state = cluster("${Constants.S3.DATA_BUCKET_PREFIX}abc")

        assertThat(teardown.stopMetrics(state)).isEqualTo(MetricsStop.Stopped("${Constants.S3.DATA_BUCKET_PREFIX}abc"))
        assertThat(emitted).isEmpty()

        val request = argumentCaptor<DeleteBucketMetricsConfigurationRequest>()
        verify(s3).deleteBucketMetricsConfiguration(request.capture())
        assertThat(request.firstValue.id()).isEqualTo(state.metricsConfigId())
        verify(s3, never()).deleteBucket(any<DeleteBucketRequest>())
    }

    @Test
    fun `a metrics failure is reported and leaves the bucket alone`() {
        doThrow(
            S3Exception
                .builder()
                .statusCode(403)
                .message("denied")
                .build(),
        ).whenever(s3)
            .deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>())

        val bucket = "${Constants.S3.DATA_BUCKET_PREFIX}abc"

        val stop = teardown.stopMetrics(cluster(bucket))

        assertThat(stop).isInstanceOf(MetricsStop.Failed::class.java)
        assertThat((stop as MetricsStop.Failed).bucket).isEqualTo(bucket)
        assertThat(stop.reason).contains("denied")
        val event = emitted.single() as Event.Teardown.DataBucketMetricsDisableFailed
        assertThat(event.bucket).isEqualTo(bucket)
        assertThat(event.reason).isEqualTo(stop.reason)
        verify(s3, never()).deleteBucket(any<DeleteBucketRequest>())
    }

    @Test
    fun `the sweep deletes the empty data buckets and keeps the ones S3 refuses to delete`() {
        val empty = "${Constants.S3.DATA_BUCKET_PREFIX}empty"
        val full = "${Constants.S3.DATA_BUCKET_PREFIX}full"
        listBuckets(empty, full)
        doThrow(
            S3Exception
                .builder()
                .statusCode(409)
                .message("BucketNotEmpty")
                .build(),
        ).whenever(s3)
            .deleteBucket(DeleteBucketRequest.builder().bucket(full).build())

        val sweep = teardown.deleteEmptyBuckets()

        assertThat(sweep).isEqualTo(DataBucketSweep(deleted = listOf(empty), kept = listOf(full)))
        assertThat(emitted).contains(Event.S3.DataBucketDeleted(empty))
        assertThat(emitted.filterIsInstance<Event.S3.DataBucketKept>().map { it.bucket }).containsExactly(full)
    }

    @Test
    fun `the sweep never touches a bucket without the data prefix or without the lab tag`() {
        val data = "${Constants.S3.DATA_BUCKET_PREFIX}one"
        val untagged = "${Constants.S3.DATA_BUCKET_PREFIX}untagged"
        val other = "someone-elses-bucket"
        listBuckets(data, untagged, other)
        whenever(s3.getBucketTagging(GetBucketTaggingRequest.builder().bucket(untagged).build()))
            .thenReturn(
                GetBucketTaggingResponse
                    .builder()
                    .tagSet(
                        Tag
                            .builder()
                            .key("owner")
                            .value("x")
                            .build(),
                    ).build(),
            )

        val sweep = teardown.deleteEmptyBuckets()

        assertThat(sweep).isEqualTo(DataBucketSweep(deleted = listOf(data), kept = emptyList()))
        verify(s3, never()).deleteBucket(DeleteBucketRequest.builder().bucket(untagged).build())
        verify(s3, never()).deleteBucket(DeleteBucketRequest.builder().bucket(other).build())
    }

    @Test
    fun `a sweep that cannot list the buckets reports it and deletes nothing`() {
        doThrow(
            S3Exception
                .builder()
                .statusCode(500)
                .message("boom")
                .build(),
        ).whenever(s3).listBuckets()

        assertThat(teardown.deleteEmptyBuckets()).isEqualTo(DataBucketSweep(emptyList(), emptyList()))

        assertThat(emitted.single()).isInstanceOf(Event.Teardown.DataBucketDiscoveryFailed::class.java)
        verify(s3, never()).deleteBucket(any<DeleteBucketRequest>())
    }
}
