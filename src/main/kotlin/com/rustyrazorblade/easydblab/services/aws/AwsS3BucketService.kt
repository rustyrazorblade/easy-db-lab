package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.S3BucketAdmin
import com.rustyrazorblade.easydblab.providers.aws.withS3BucketPolicyRetry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.MetricsConfiguration
import software.amazon.awssdk.services.s3.model.MetricsFilter
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging
import java.util.UUID

/**
 * Return value from [AwsS3BucketService.configureDataBucket].
 */
data class DataBucketConfig(
    val bucketName: String,
    val metricsConfigId: String,
)

/**
 * The one S3 bucket adapter, on [S3Client]: the [S3BucketAdmin] operations plus the account-bucket
 * and per-cluster data-bucket workflows built from them. Commands use this service for bucket work;
 * nothing else holds the S3 client for bucket administration.
 */
class AwsS3BucketService(
    private val s3Client: S3Client,
    private val accountIdentity: AccountIdentity,
) : S3BucketAdmin,
    KoinComponent {
    private val eventBus: EventBus by inject()
    private val userConfigProvider: UserConfigProvider by inject()
    private val context: Context by inject()

    /**
     * Ensures the account-level S3 bucket exists and is recorded in the user config, creating it
     * if [User.s3Bucket] is blank. Idempotent: returns the existing bucket name if already set.
     *
     * This lets profiles that predate (or never completed) bucket setup migrate automatically —
     * `up` and the AMI build path call this so the bucket (and the S3 build cache it backs) come
     * online without re-running `profile setup`.
     *
     * It reads nothing from S3 for a bucket already set, because path resolution calls it often.
     * The setup paths add a missing marker tag with [ensureMarkerTags].
     */
    fun ensureAccountBucket(user: User): String {
        if (user.s3Bucket.isNotBlank()) return user.s3Bucket
        return createAccountBucket(user)
    }

    /**
     * [ensureAccountBucket], then [ensureMarkerTags] on a bucket the profile already named. A
     * bucket made by this call already carries every marker, so it is not read back.
     */
    fun ensureMarkedAccountBucket(user: User): String {
        if (user.s3Bucket.isBlank()) return createAccountBucket(user)
        ensureMarkerTags(user.s3Bucket)
        return user.s3Bucket
    }

    private fun createAccountBucket(user: User): String {
        eventBus.emit(Event.Setup.S3BucketCreating)
        val bucketName = "easy-db-lab-${UUID.randomUUID()}"
        createBucket(bucketName)
        putBucketPolicy(bucketName)
        tagBucket(
            bucketName,
            mapOf(
                Constants.S3.PROFILE_TAG_KEY to context.profile,
                Constants.S3.OWNER_TAG_KEY to user.email,
            ) + Constants.Vpc.MARKER_TAGS,
        )
        user.s3Bucket = bucketName
        userConfigProvider.saveUserConfig(user)
        eventBus.emit(Event.Setup.S3BucketCreated(bucketName))
        return bucketName
    }

    override fun createBucket(bucketName: String): String {
        requireValidBucketName(bucketName)

        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())
            log.info { "Created S3 bucket: $bucketName" }
        } catch (e: BucketAlreadyOwnedByYouException) {
            log.info { "S3 bucket already exists and is owned by you: $bucketName" }
        } catch (e: BucketAlreadyExistsException) {
            log.info { "S3 bucket already exists: $bucketName" }
        } catch (e: S3Exception) {
            log.error(e) { "Failed to create S3 bucket: $bucketName - ${e.message}" }
            throw e
        }

        return bucketName
    }

    override fun putBucketPolicy(bucketName: String) {
        requireValidBucketName(bucketName)

        val bucketPolicy = AWSPolicy.Inline.S3BucketPolicy(accountIdentity.getAccountId(), bucketName).toJson()
        val request =
            PutBucketPolicyRequest
                .builder()
                .bucket(bucketName)
                .policy(bucketPolicy)
                .build()

        try {
            // The policy references the just-created IAM roles as principals. IAM is eventually
            // consistent, so S3 may briefly reject the policy with "Invalid principal in policy"
            // until the roles propagate. Retry with backoff so this resolves itself.
            withS3BucketPolicyRetry("put-bucket-policy") {
                s3Client.putBucketPolicy(request)
            }
            log.info { "\u2713 Applied S3 bucket policy granting access to all 3 IAM roles: $bucketName" }
        } catch (e: S3Exception) {
            log.error(e) { "Failed to apply S3 bucket policy: $bucketName - ${e.message}" }
            val isPrincipalPropagation =
                e.awsErrorDetails()?.errorCode() == "MalformedPolicy" ||
                    e.message?.contains("Invalid principal") == true
            if (isPrincipalPropagation) {
                throw IllegalStateException(
                    "Could not apply the S3 bucket policy because the easy-db-lab IAM roles are not yet " +
                        "visible to S3 (IAM propagation delay). Wait a moment and re-run profile setup.",
                    e,
                )
            }
            throw e
        }
    }

    override fun tagBucket(
        bucketName: String,
        tags: Map<String, String>,
    ) {
        requireValidBucketName(bucketName)

        val s3Tags =
            tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }
        val request =
            PutBucketTaggingRequest
                .builder()
                .bucket(bucketName)
                .tagging(Tagging.builder().tagSet(s3Tags).build())
                .build()

        s3Client.putBucketTagging(request)
        log.info { "\u2713 Applied tags to S3 bucket: $bucketName (${tags.keys.joinToString(", ")})" }
    }

    /**
     * Adds every marker tag ([Constants.Vpc.MARKER_TAGS]) that [bucketName] lacks, keeping its
     * other tags. PutBucketTagging replaces the whole set, so the current set is read first and
     * written back merged; a bucket with no tags at all (`NoSuchTagSet`) starts from an empty set.
     * Nothing is written when every marker is already there.
     */
    fun ensureMarkerTags(bucketName: String) {
        val current = bucketTags(bucketName)
        val merged = current + Constants.Vpc.MARKER_TAGS
        if (merged == current) {
            return
        }
        tagBucket(bucketName, merged)
        log.info { "Added the missing marker tags to S3 bucket: $bucketName" }
    }

    /** The tags on [bucketName]; empty when it has none (S3 answers `NoSuchTagSet`). */
    private fun bucketTags(bucketName: String): Map<String, String> =
        try {
            s3Client
                .getBucketTagging(GetBucketTaggingRequest.builder().bucket(bucketName).build())
                .tagSet()
                .associate { it.key() to it.value() }
        } catch (e: S3Exception) {
            if (e.awsErrorDetails()?.errorCode() != NO_SUCH_TAG_SET) throw e
            emptyMap()
        }

    override fun enableBucketRequestMetrics(
        bucketName: String,
        prefix: String?,
        configId: String,
    ) {
        val metricsConfigBuilder = MetricsConfiguration.builder().id(configId)
        if (prefix != null) {
            metricsConfigBuilder.filter(MetricsFilter.builder().prefix(prefix).build())
        }

        val request =
            PutBucketMetricsConfigurationRequest
                .builder()
                .bucket(bucketName)
                .id(configId)
                .metricsConfiguration(metricsConfigBuilder.build())
                .build()

        val retry = Retry.of("s3-enable-bucket-metrics", RetryUtil.createAwsRetryConfig<Unit>())
        Retry
            .decorateRunnable(retry) {
                s3Client.putBucketMetricsConfiguration(request)
                log.info { "Enabled S3 request metrics on bucket: $bucketName (prefix: ${prefix ?: "whole-bucket"})" }
            }.run()
    }

    override fun disableBucketRequestMetrics(
        bucketName: String,
        configId: String,
    ) {
        val request =
            DeleteBucketMetricsConfigurationRequest
                .builder()
                .bucket(bucketName)
                .id(configId)
                .build()

        try {
            val retry = Retry.of("s3-disable-bucket-metrics", RetryUtil.createAwsRetryConfig<Unit>())
            Retry
                .decorateRunnable(retry) {
                    s3Client.deleteBucketMetricsConfiguration(request)
                }.run()
            log.info { "Disabled S3 request metrics on bucket: $bucketName (configId: $configId)" }
        } catch (e: S3Exception) {
            if (e.statusCode() == Constants.HttpStatus.NOT_FOUND) {
                log.info { "No metrics configuration found on bucket: $bucketName for configId: $configId (already disabled)" }
            } else {
                throw e
            }
        }
    }

    override fun findDataBuckets(): List<String> =
        s3Client
            .listBuckets()
            .buckets()
            .filter { it.name().startsWith(Constants.S3.DATA_BUCKET_PREFIX) }
            .mapNotNull { bucket ->
                try {
                    val taggingRequest = GetBucketTaggingRequest.builder().bucket(bucket.name()).build()
                    val hasTag =
                        s3Client.getBucketTagging(taggingRequest).tagSet().any {
                            it.key() == Constants.Vpc.LEGACY_TAG_KEY && it.value() == Constants.Vpc.TAG_VALUE
                        }
                    if (hasTag) bucket.name() else null
                } catch (e: S3Exception) {
                    log.debug { "Could not get tags for bucket ${bucket.name()}: ${e.message}" }
                    null
                }
            }

    override fun deleteEmptyBucket(bucketName: String): Result<Unit> =
        try {
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
            log.info { "Deleted S3 bucket: $bucketName" }
            Result.success(Unit)
        } catch (e: S3Exception) {
            log.warn { "Could not delete bucket $bucketName: ${e.message}" }
            Result.failure(e)
        }

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
            Constants.Vpc.MARKER_TAGS +
                mapOf(
                    Constants.S3.CLUSTER_ID_TAG_KEY to clusterId,
                    Constants.S3.CLUSTER_NAME_TAG_KEY to clusterName,
                ),
        )
        eventBus.emit(Event.S3.DataBucketCreated(bucketName))

        enableBucketRequestMetrics(bucketName, null, metricsConfigId)
        eventBus.emit(Event.S3.MetricsEnabled(bucketName))

        return DataBucketConfig(bucketName, metricsConfigId)
    }

    /**
     * Tears down a per-cluster data bucket by disabling its request metrics. The bucket's objects
     * are the owner's data, so no expiry is set and nothing is deleted.
     */
    fun teardownDataBucket(
        bucketName: String,
        metricsConfigId: String,
    ) {
        disableBucketRequestMetrics(bucketName, metricsConfigId)
    }

    private companion object {
        private val log = KotlinLogging.logger {}
        private const val NO_SUCH_TAG_SET = "NoSuchTagSet"
        private val BUCKET_NAME = Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$")

        fun requireValidBucketName(bucketName: String) {
            require(bucketName.matches(BUCKET_NAME)) {
                "Invalid S3 bucket name: $bucketName. Must be lowercase, 3-63 chars, alphanumeric and hyphens only."
            }
        }
    }
}
