package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.aws
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag

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
    fun `a new account bucket carries both marker tags`() {
        get<ProfileStore>().replace(TestProfiles.profile(accountBucket = ""))

        val bucketName = service.ensureAccountBucket()

        val request = argumentCaptor<PutBucketTaggingRequest>()
        verify(mockS3Client).putBucketTagging(request.capture())
        val tags =
            request.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() }
        assertThat(request.firstValue.bucket()).isEqualTo(bucketName)
        assertThat(tags)
            .containsEntry(Constants.Vpc.LEGACY_TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
            .containsEntry("Owner", TestProfiles.EMAIL)
        assertThat(get<ProfileStore>().load().aws.accountBucket).isEqualTo(bucketName)
    }

    /**
     * Two operations of one command each need the bucket; the second must see the bucket the first
     * saved, though the profile it was handed at start had none.
     *
     * Control: read the bucket from an AwsDestination bound once in Koin; the second call creates
     * a second bucket.
     */
    @Test
    fun `two ensures with one stale profile create one bucket`() {
        get<ProfileStore>().replace(TestProfiles.profile(accountBucket = ""))
        val staleProfile = get<ProfileStore>().load()

        val first = service.ensureAccountBucket()
        val second = service.ensureAccountBucket()

        assertThat(staleProfile.aws.accountBucket).isEmpty()
        assertThat(second).isEqualTo(first)
        verify(mockS3Client).createBucket(any<CreateBucketRequest>())
        assertThat(get<ProfileStore>().load().aws.accountBucket).isEqualTo(first)
    }

    private fun s3Tags(vararg tags: Pair<String, String>) =
        tags.map { (key, value) ->
            Tag
                .builder()
                .key(key)
                .value(value)
                .build()
        }

    /** Path resolution calls ensureAccountBucket often; for a bucket already set it must not touch S3. */
    @Test
    fun `an account bucket already in the profile is returned without any S3 call`() {
        get<ProfileStore>().replace(TestProfiles.profile(accountBucket = "easy-db-lab-existing"))

        assertThat(service.ensureAccountBucket()).isEqualTo("easy-db-lab-existing")

        verifyNoInteractions(mockS3Client)
    }

    /** A bucket made in this call already carries both markers; reading it back would tag it twice. */
    @Test
    fun `a marked account bucket created now is tagged once and never read back`() {
        get<ProfileStore>().replace(TestProfiles.profile(accountBucket = ""))

        val bucketName = service.ensureMarkedAccountBucket()

        assertThat(bucketName).startsWith("easy-db-lab-")
        verify(mockS3Client).putBucketTagging(any<PutBucketTaggingRequest>())
        verify(mockS3Client, never()).getBucketTagging(any<GetBucketTaggingRequest>())
    }

    @Test
    fun `a marked account bucket the profile already names gains a missing marker`() {
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(
            GetBucketTaggingResponse.builder().tagSet(s3Tags("easy_cass_lab" to "1")).build(),
        )
        get<ProfileStore>().replace(TestProfiles.profile(accountBucket = "easy-db-lab-existing"))

        assertThat(service.ensureMarkedAccountBucket()).isEqualTo("easy-db-lab-existing")

        val request = argumentCaptor<PutBucketTaggingRequest>()
        verify(mockS3Client).putBucketTagging(request.capture())
        assertThat(
            request.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() },
        ).containsExactlyInAnyOrderEntriesOf(mapOf("easy_cass_lab" to "1", "easy_db_lab" to "1"))
        verify(mockS3Client, never()).createBucket(any<CreateBucketRequest>())
    }

    @Test
    fun `a bucket with only the legacy marker gains the new one and keeps its other tags`() {
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(
            GetBucketTaggingResponse
                .builder()
                .tagSet(s3Tags("easy_cass_lab" to "1", "Profile" to "default", "Owner" to "someone@example.com"))
                .build(),
        )

        service.ensureMarkerTags("easy-db-lab-existing")

        val request = argumentCaptor<PutBucketTaggingRequest>()
        verify(mockS3Client).putBucketTagging(request.capture())
        assertThat(request.firstValue.bucket()).isEqualTo("easy-db-lab-existing")
        assertThat(
            request.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() },
        ).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "easy_cass_lab" to "1",
                "easy_db_lab" to "1",
                "Profile" to "default",
                "Owner" to "someone@example.com",
            ),
        )
        verify(mockS3Client, never()).createBucket(any<CreateBucketRequest>())
    }

    @Test
    fun `a bucket that carries both markers is not rewritten`() {
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(
            GetBucketTaggingResponse.builder().tagSet(s3Tags("easy_db_lab" to "1", "easy_cass_lab" to "1")).build(),
        )

        service.ensureMarkerTags("easy-db-lab-existing")

        verify(mockS3Client, never()).putBucketTagging(any<PutBucketTaggingRequest>())
    }

    @Test
    fun `a bucket with no tags at all gets both markers`() {
        val noTagSet =
            S3Exception
                .builder()
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchTagSet").build())
                .build()
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>())).thenThrow(noTagSet)

        service.ensureMarkerTags("easy-db-lab-existing")

        val request = argumentCaptor<PutBucketTaggingRequest>()
        verify(mockS3Client).putBucketTagging(request.capture())
        assertThat(
            request.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() },
        ).containsExactlyInAnyOrderEntriesOf(mapOf("easy_cass_lab" to "1", "easy_db_lab" to "1"))
    }

    @Test
    fun `any other failure reading a bucket's tags is rethrown and nothing is written`() {
        val denied =
            S3Exception
                .builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .build()
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>())).thenThrow(denied)

        assertThatThrownBy { service.ensureMarkerTags("easy-db-lab-existing") }.isSameAs(denied)
        verify(mockS3Client, never()).putBucketTagging(any<PutBucketTaggingRequest>())
    }

    @Test
    fun `the data bucket carries both marker tags and the cluster metadata`() {
        service.configureDataBucket("data-bucket", "cluster-123", "my-cluster", "metrics-id")

        val request = argumentCaptor<PutBucketTaggingRequest>()
        verify(mockS3Client).putBucketTagging(request.capture())
        val tags =
            request.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() }
        assertThat(tags).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                Constants.Vpc.LEGACY_TAG_KEY to Constants.Vpc.TAG_VALUE,
                Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE,
                "cluster_id" to "cluster-123",
                "cluster_name" to "my-cluster",
            ),
        )
    }

    @Test
    fun `configureDataBucket emits correct events`() {
        val emitted = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted.add(envelope.event)
                }

                override fun close() = Unit
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
    fun `teardownDataBucket disables metrics and sets no expiry on the owner's data`() {
        service.teardownDataBucket("data-bucket", "metrics-id")

        verify(mockS3Client).deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>())
        verify(mockS3Client, never()).putBucketLifecycleConfiguration(any<PutBucketLifecycleConfigurationRequest>())
    }
}
