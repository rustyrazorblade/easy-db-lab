package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [AwsS3BucketService] verifying delegation to [AWS].
 */
class AwsS3BucketServiceTest {
    private lateinit var mockAws: AWS
    private lateinit var service: AwsS3BucketService

    @BeforeEach
    fun setup() {
        mockAws = mock()
        service = AwsS3BucketService(mockAws)
    }

    @Test
    fun `createBucket delegates to aws`() {
        whenever(mockAws.createS3Bucket("my-bucket")).thenReturn("my-bucket")

        val result = service.createBucket("my-bucket")

        assertThat(result).isEqualTo("my-bucket")
        verify(mockAws).createS3Bucket("my-bucket")
    }

    @Test
    fun `putBucketPolicy delegates to aws`() {
        service.putBucketPolicy("my-bucket")

        verify(mockAws).putS3BucketPolicy("my-bucket")
    }

    @Test
    fun `tagBucket delegates to aws`() {
        val tags = mapOf("key" to "value")

        service.tagBucket("my-bucket", tags)

        verify(mockAws).tagS3Bucket("my-bucket", tags)
    }

    @Test
    fun `enableBucketRequestMetrics delegates to aws`() {
        service.enableBucketRequestMetrics("my-bucket", "prefix/", "config-id")

        verify(mockAws).enableBucketRequestMetrics("my-bucket", "prefix/", "config-id")
    }

    @Test
    fun `disableBucketRequestMetrics delegates to aws`() {
        service.disableBucketRequestMetrics("my-bucket", "config-id")

        verify(mockAws).disableBucketRequestMetrics("my-bucket", "config-id")
    }

    @Test
    fun `setLifecycleExpirationRule delegates to aws`() {
        service.setLifecycleExpirationRule("my-bucket", "prefix/", 7)

        verify(mockAws).setLifecycleExpirationRule("my-bucket", "prefix/", 7)
    }

    @Test
    fun `findBucketByTag delegates to aws`() {
        whenever(mockAws.findS3BucketByTag("key", "value")).thenReturn("found-bucket")

        val result = service.findBucketByTag("key", "value")

        assertThat(result).isEqualTo("found-bucket")
        verify(mockAws).findS3BucketByTag("key", "value")
    }

    @Test
    fun `attachS3Policy delegates to aws`() {
        service.attachS3Policy("my-role")

        verify(mockAws).attachS3Policy("my-role")
    }
}
