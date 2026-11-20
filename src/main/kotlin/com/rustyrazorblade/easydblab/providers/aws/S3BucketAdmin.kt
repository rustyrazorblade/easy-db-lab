package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * Administration of S3 buckets: creating them, granting the easy-db-lab roles access, tagging them,
 * switching CloudWatch request metrics, finding the per-cluster data buckets and deleting empty ones.
 *
 * It is the contract the S3 adapter ([com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService])
 * implements, so a caller that only administers buckets does not depend on the IAM or STS clients.
 */
interface S3BucketAdmin {
    /**
     * Creates a bucket. Idempotent: succeeds when the bucket already exists and is yours.
     *
     * @param bucketName a valid S3 bucket name (lowercase, 3-63 chars)
     * @return the bucket name
     * @throws IllegalArgumentException if the bucket name is invalid
     */
    fun createBucket(bucketName: String): String

    /**
     * Applies the bucket policy that grants access to all easy-db-lab IAM roles. Idempotent.
     *
     * @throws IllegalArgumentException if the bucket name is invalid
     * @throws S3Exception if S3 refuses the policy
     */
    fun putBucketPolicy(bucketName: String)

    /**
     * Tags a bucket. Replaces ALL existing tags on the bucket.
     *
     * @throws IllegalArgumentException if the bucket name is invalid
     * @throws S3Exception if S3 refuses (for example the bucket does not exist)
     */
    fun tagBucket(
        bucketName: String,
        tags: Map<String, String>,
    )

    /**
     * Enables S3 request metrics on a bucket. Idempotent: overwrites a configuration with the same ID.
     *
     * @param prefix optional prefix to scope the metrics to one folder or cluster
     */
    fun enableBucketRequestMetrics(
        bucketName: String,
        prefix: String? = null,
        configId: String,
    )

    /** Disables S3 request metrics on a bucket. Safe to call when they were never enabled. */
    fun disableBucketRequestMetrics(
        bucketName: String,
        configId: String,
    )

    /** Every per-cluster data bucket (the data bucket prefix) that carries the legacy marker tag (`Constants.Vpc.LEGACY_TAG_KEY`). */
    fun findDataBuckets(): List<String>

    /**
     * Deletes a bucket if it is empty. S3 refuses to delete a bucket that still holds objects, so a
     * non-empty bucket is left in place.
     *
     * @return success if deleted; failure carrying S3's error otherwise
     */
    fun deleteEmptyBucket(bucketName: String): Result<Unit>
}
