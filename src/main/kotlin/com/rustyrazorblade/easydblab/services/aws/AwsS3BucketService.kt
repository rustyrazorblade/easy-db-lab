package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.AWS

/**
 * Service for S3 bucket administration operations.
 *
 * Wraps low-level S3 operations from [AWS] to provide a clean service-layer
 * interface for commands. Commands should use this service instead of
 * accessing [AWS] directly for bucket operations.
 */
class AwsS3BucketService(
    private val aws: AWS,
) {
    /**
     * Creates an S3 bucket with the specified name.
     * Idempotent - will succeed even if bucket already exists and is owned by you.
     */
    fun createBucket(bucketName: String): String = aws.createS3Bucket(bucketName)

    /**
     * Applies an S3 bucket policy granting access to all easy-db-lab IAM roles.
     */
    fun putBucketPolicy(bucketName: String) = aws.putS3BucketPolicy(bucketName)

    /**
     * Applies tags to an existing S3 bucket.
     */
    fun tagBucket(
        bucketName: String,
        tags: Map<String, String>,
    ) = aws.tagS3Bucket(bucketName, tags)

    /**
     * Enables S3 request metrics on a bucket for CloudWatch monitoring.
     */
    fun enableBucketRequestMetrics(
        bucketName: String,
        prefix: String? = null,
        configId: String,
    ) = aws.enableBucketRequestMetrics(bucketName, prefix, configId)

    /**
     * Disables S3 request metrics on a bucket.
     */
    fun disableBucketRequestMetrics(
        bucketName: String,
        configId: String,
    ) = aws.disableBucketRequestMetrics(bucketName, configId)

    /**
     * Sets an S3 lifecycle expiration rule on a prefix within a bucket.
     */
    fun setLifecycleExpirationRule(
        bucketName: String,
        prefix: String,
        days: Int,
    ) = aws.setLifecycleExpirationRule(bucketName, prefix, days)

    /**
     * Finds an S3 bucket by a specific tag key-value pair.
     */
    fun findBucketByTag(
        tagKey: String,
        tagValue: String,
    ): String? = aws.findS3BucketByTag(tagKey, tagValue)

    /**
     * Attaches an inline S3 access policy to an IAM role.
     */
    fun attachS3Policy(roleName: String) = aws.attachS3Policy(roleName)
}
