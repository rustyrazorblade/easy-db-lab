package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * Return value from [AwsS3BucketService.configureDataBucket].
 */
data class DataBucketConfig(
    val bucketName: String,
    val metricsConfigId: String,
)

/**
 * Service for S3 bucket administration operations.
 *
 * Wraps low-level S3 operations from [AWS] to provide a clean service-layer
 * interface for commands. Commands should use this service instead of
 * accessing [AWS] directly for bucket operations.
 */
class AwsS3BucketService(
    private val aws: AWS,
) : KoinComponent {
    private val eventBus: EventBus by inject()
    private val userConfigProvider: UserConfigProvider by inject()
    private val context: Context by inject()

    /**
     * Ensures the account-level S3 bucket exists and is recorded in the user config, creating it
     * if [User.s3Bucket] is blank. Idempotent: returns the existing bucket name if already set.
     *
     * This lets profiles that predate (or never completed) bucket setup migrate automatically —
     * `up` and the AMI build path call this so the bucket (and the S3 build cache it backs) come
     * online without re-running `setup-profile`.
     */
    fun ensureAccountBucket(user: User): String {
        if (user.s3Bucket.isNotBlank()) {
            return user.s3Bucket
        }
        eventBus.emit(Event.Setup.S3BucketCreating)
        val bucketName = "easy-db-lab-${UUID.randomUUID()}"
        aws.createS3Bucket(bucketName)
        aws.putS3BucketPolicy(bucketName)
        aws.tagS3Bucket(
            bucketName,
            mapOf(
                "Profile" to context.profile,
                "Owner" to user.email,
                "easy_cass_lab" to "1",
            ),
        )
        user.s3Bucket = bucketName
        userConfigProvider.saveUserConfig(user)
        eventBus.emit(Event.Setup.S3BucketCreated(bucketName))
        return bucketName
    }

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

    /**
     * Finds all per-cluster data buckets (easy-db-lab-data-*) tagged with easy_cass_lab.
     */
    fun findDataBuckets(): List<String> = aws.findDataBuckets()

    /**
     * Deletes an S3 bucket. Returns true if deleted, false if non-empty or error.
     */
    fun deleteEmptyBucket(bucketName: String): Boolean = aws.deleteS3Bucket(bucketName)

    /**
     * Sets a lifecycle expiration rule on an entire bucket (no prefix filter).
     */
    fun setFullBucketLifecycleExpiration(
        bucketName: String,
        days: Int,
    ) = aws.setFullBucketLifecycleExpiration(bucketName, days)

    /**
     * Creates and fully configures a per-cluster data bucket.
     *
     * Orchestrates: bucket creation, policy application, tagging,
     * and CloudWatch request metrics enablement.
     */
    fun configureDataBucket(
        bucketName: String,
        clusterId: String,
        clusterName: String,
        metricsConfigId: String,
    ): DataBucketConfig {
        eventBus.emit(Event.S3.DataBucketCreating(bucketName))
        createBucket(bucketName)
        putBucketPolicy(bucketName)
        tagBucket(
            bucketName,
            mapOf(
                Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE,
                "cluster_id" to clusterId,
                "cluster_name" to clusterName,
            ),
        )
        eventBus.emit(Event.S3.DataBucketCreated(bucketName))

        enableBucketRequestMetrics(bucketName, null, metricsConfigId)
        eventBus.emit(Event.S3.MetricsEnabled(bucketName))

        return DataBucketConfig(bucketName, metricsConfigId)
    }

    /**
     * Tears down a per-cluster data bucket by disabling metrics and setting lifecycle expiration.
     */
    fun teardownDataBucket(
        bucketName: String,
        metricsConfigId: String,
        retentionDays: Int,
    ) {
        disableBucketRequestMetrics(bucketName, metricsConfigId)
        setFullBucketLifecycleExpiration(bucketName, retentionDays)
        eventBus.emit(Event.S3.DataBucketExpiring(bucketName, retentionDays))
    }
}
