package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse
import software.amazon.awssdk.services.ec2.model.KeyPairInfo
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.ec2.model.Tag as Ec2Tag
import software.amazon.awssdk.services.s3.model.Tag as S3Tag

/**
 * The image builds' backfill: a profile made before the `easy_db_lab` marker has a bucket and a
 * key pair with only the legacy one, and both must gain it while keeping their other tags. Runs the
 * real bucket and key-pair services over mocked S3 and EC2 clients.
 */
class ProfileResourceMarkersTest : BaseKoinTest() {
    private val ec2: Ec2Client = mock()

    private fun legacyBucketTags(): GetBucketTaggingResponse =
        GetBucketTaggingResponse
            .builder()
            .tagSet(
                S3Tag
                    .builder()
                    .key("easy_cass_lab")
                    .value("1")
                    .build(),
                S3Tag
                    .builder()
                    .key("Profile")
                    .value("default")
                    .build(),
            ).build()

    private fun legacyKeyPair(): DescribeKeyPairsResponse =
        DescribeKeyPairsResponse
            .builder()
            .keyPairs(
                KeyPairInfo
                    .builder()
                    .keyName("lab-key")
                    .keyPairId("key-0abc")
                    .tags(
                        Ec2Tag
                            .builder()
                            .key("easy_cass_lab")
                            .value("1")
                            .build(),
                    ).build(),
            ).build()

    @Test
    fun `a legacy-only bucket and key pair both gain the new marker`() {
        val s3: S3Client = get()
        whenever(s3.getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(legacyBucketTags())
        val factory: AWSClientFactory = mock()
        whenever(factory.createEc2ClientWithProfile("lab", "us-west-2")).thenReturn(ec2)
        whenever(ec2.describeKeyPairs(any<DescribeKeyPairsRequest>())).thenReturn(legacyKeyPair())
        val user =
            get<User>().apply {
                s3Bucket = "easy-db-lab-existing"
                keyName = "lab-key"
                awsProfile = "lab"
                region = "us-west-2"
            }
        val markers =
            ProfileResourceMarkers(
                get<AwsS3BucketService>(),
                AwsKeyPairService(get<EventBus>(), factory, AwsErrorHandler()),
            )

        assertThat(markers.ensureAccountBucketAndKeyPair(user)).isEqualTo("easy-db-lab-existing")

        val bucketTags = argumentCaptor<PutBucketTaggingRequest>()
        verify(s3).putBucketTagging(bucketTags.capture())
        assertThat(bucketTags.firstValue.bucket()).isEqualTo("easy-db-lab-existing")
        assertThat(
            bucketTags.firstValue
                .tagging()
                .tagSet()
                .associate { it.key() to it.value() },
        ).containsExactlyInAnyOrderEntriesOf(
            mapOf("easy_cass_lab" to "1", "easy_db_lab" to "1", "Profile" to "default"),
        )
        val keyTags = argumentCaptor<CreateTagsRequest>()
        verify(ec2).createTags(keyTags.capture())
        assertThat(keyTags.firstValue.resources()).containsExactly("key-0abc")
        assertThat(keyTags.firstValue.tags().associate { it.key() to it.value() })
            .containsExactlyEntriesOf(mapOf("easy_db_lab" to "1"))
    }
}
