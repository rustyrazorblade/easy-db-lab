package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.providers.aws.StsAccountIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging

/**
 * The account bucket's marker backfill against a real S3 API (LocalStack): how S3 reports a
 * bucket with no tags, and that PutBucketTagging, which replaces the whole set, keeps every tag
 * the bucket already had.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsS3BucketServiceMarkerTagsIntegrationTest {
    private lateinit var s3Client: S3Client
    private lateinit var service: AwsS3BucketService

    @BeforeAll
    fun setup() {
        s3Client = SharedLocalStack.s3Client()
        service = AwsS3BucketService(s3Client, StsAccountIdentity(SharedLocalStack.stsClient()))
    }

    private fun bucket(name: String): String = "$name-${System.nanoTime()}".also { SharedLocalStack.createBucketIfMissing(s3Client, it) }

    private fun tagsOf(bucket: String): Map<String, String> =
        try {
            s3Client
                .getBucketTagging(GetBucketTaggingRequest.builder().bucket(bucket).build())
                .tagSet()
                .associate { it.key() to it.value() }
        } catch (e: S3Exception) {
            if (e.awsErrorDetails()?.errorCode() != "NoSuchTagSet") throw e
            emptyMap()
        }

    private fun putTags(
        bucket: String,
        tags: Map<String, String>,
    ) {
        s3Client.putBucketTagging(
            PutBucketTaggingRequest
                .builder()
                .bucket(bucket)
                .tagging(
                    Tagging
                        .builder()
                        .tagSet(
                            tags.map { (key, value) ->
                                Tag
                                    .builder()
                                    .key(key)
                                    .value(value)
                                    .build()
                            },
                        ).build(),
                ).build(),
        )
    }

    @Test
    fun `a bucket with no tags gets both markers`() {
        val name = bucket("marker-untagged")

        service.ensureMarkerTags(name)

        assertThat(tagsOf(name)).containsExactlyInAnyOrderEntriesOf(Constants.Vpc.MARKER_TAGS)
    }

    @Test
    fun `a bucket with the legacy marker gains the new one and keeps its other tags`() {
        val name = bucket("marker-legacy")
        putTags(name, mapOf(Constants.Vpc.LEGACY_TAG_KEY to Constants.Vpc.TAG_VALUE, "Profile" to "default", "Purpose" to "kept"))

        service.ensureMarkerTags(name)

        assertThat(tagsOf(name)).containsExactlyInAnyOrderEntriesOf(
            Constants.Vpc.MARKER_TAGS + mapOf("Profile" to "default", "Purpose" to "kept"),
        )
    }

    @Test
    fun `a second backfill leaves the tags as the first wrote them`() {
        val name = bucket("marker-twice")
        putTags(name, mapOf(Constants.Vpc.LEGACY_TAG_KEY to Constants.Vpc.TAG_VALUE, "Purpose" to "kept"))

        service.ensureMarkerTags(name)
        val afterFirst = tagsOf(name)
        service.ensureMarkerTags(name)

        assertThat(tagsOf(name)).isEqualTo(afterFirst)
    }
}
