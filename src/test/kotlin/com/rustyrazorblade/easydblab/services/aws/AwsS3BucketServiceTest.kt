package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest

/**
 * Tests for [AwsS3BucketService].
 *
 * Extends BaseKoinTest to get mocked AWS infrastructure from Koin.
 * The S3Client is mocked to prevent real AWS API calls.
 */
class AwsS3BucketServiceTest : BaseKoinTest() {
    private lateinit var mockS3Client: S3Client
    private lateinit var service: AwsS3BucketService
    private lateinit var eventBus: EventBus

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                // Replace the default S3Client mock with our custom one
                single {
                    mock<S3Client>().also {
                        mockS3Client = it
                        // Set up default behavior to prevent null returns
                        whenever(it.createBucket(any<CreateBucketRequest>())).thenReturn(null)
                        whenever(it.putBucketPolicy(any<PutBucketPolicyRequest>())).thenReturn(null)
                        whenever(it.putBucketTagging(any<PutBucketTaggingRequest>())).thenReturn(null)
                        whenever(it.putBucketMetricsConfiguration(any<PutBucketMetricsConfigurationRequest>()))
                            .thenReturn(null)
                        whenever(it.deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>()))
                            .thenReturn(null)
                        whenever(it.putBucketLifecycleConfiguration(any<PutBucketLifecycleConfigurationRequest>()))
                            .thenReturn(null)
                    }
                }
            },
        )

    @BeforeEach
    fun setup() {
        service = get()
        eventBus = get()
    }

    @Test
    fun `configureDataBucket creates bucket with policy tags and metrics`() {
        val result =
            service.configureDataBucket(
                bucketName = "data-bucket",
                clusterId = "cluster-123",
                clusterName = "my-cluster",
                metricsConfigId = "metrics-id",
            )

        assertThat(result.bucketName).isEqualTo("data-bucket")
        assertThat(result.metricsConfigId).isEqualTo("metrics-id")

        // Verify S3 operations were called
        verify(mockS3Client).createBucket(any<CreateBucketRequest>())
        verify(mockS3Client).putBucketPolicy(any<PutBucketPolicyRequest>())
        verify(mockS3Client).putBucketTagging(any<PutBucketTaggingRequest>())
        verify(mockS3Client).putBucketMetricsConfiguration(any<PutBucketMetricsConfigurationRequest>())
    }

    @Test
    fun `configureDataBucket emits correct events`() {
        val emitted = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted.add(envelope.event)
                }

                override fun close() {}
            },
        )

        service.configureDataBucket(
            bucketName = "data-bucket",
            clusterId = "cluster-123",
            clusterName = "my-cluster",
            metricsConfigId = "metrics-id",
        )

        assertThat(emitted).containsExactly(
            Event.S3.DataBucketCreating("data-bucket"),
            Event.S3.DataBucketCreated("data-bucket"),
            Event.S3.MetricsEnabled("data-bucket"),
        )
    }

    @Test
    fun `teardownDataBucket disables metrics and sets lifecycle`() {
        service.teardownDataBucket("data-bucket", "metrics-id", 7)

        verify(mockS3Client).deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>())
        verify(mockS3Client).putBucketLifecycleConfiguration(any<PutBucketLifecycleConfigurationRequest>())
    }

    @Test
    fun `teardownDataBucket emits expiring event`() {
        val emitted = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted.add(envelope.event)
                }

                override fun close() {}
            },
        )

        service.teardownDataBucket("data-bucket", "metrics-id", 3)

        assertThat(emitted).containsExactly(
            Event.S3.DataBucketExpiring("data-bucket", 3),
        )
    }
}
