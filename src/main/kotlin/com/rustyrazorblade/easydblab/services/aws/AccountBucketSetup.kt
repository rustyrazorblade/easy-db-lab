package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Makes the account bucket ready for a cluster before `up` provisions anything: the account and
 * per-cluster data buckets, the role and bucket policies, and the account compactor that works on
 * the bucket's observability data. It exists so `up` delegates the whole bucket step to one place.
 */
class AccountBucketSetup(
    private val s3BucketService: AwsS3BucketService,
    private val userConfig: User,
    private val clusterStateManager: ClusterStateManager,
    private val compactorService: CompactorService,
    private val eventBus: EventBus,
) {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    /** Configures the buckets for [state], saving it when they change, and starts the account compactor. */
    fun prepare(state: ClusterState) {
        configureBuckets(state)
        val bucket = requireBucket(state)
        reapplyPolicies(bucket)
        compactorService.ensureRunning(bucket)
    }

    /**
     * Configures the account-level S3 bucket and per-cluster data bucket.
     * Uses the bucket name from the user profile (set during profile setup).
     * Applies bucket policy for IAM role access.
     * Creates a per-cluster data bucket for ClickHouse data and CloudWatch metrics.
     */
    private fun configureBuckets(state: ClusterState) {
        if (!state.s3Bucket.isNullOrBlank() && state.dataBucket.isNotBlank()) {
            log.info { "S3 buckets already configured: account=${state.s3Bucket}, data=${state.dataBucket}" }
            return
        }

        // Ensure-or-create the account bucket (migrates profiles that never had one saved)
        val accountBucket = s3BucketService.ensureAccountBucket(userConfig)

        // Configure account-level bucket
        eventBus.emit(Event.S3.BucketUsing(accountBucket))
        s3BucketService.putBucketPolicy(accountBucket)
        state.s3Bucket = accountBucket
        eventBus.emit(Event.S3.BucketConfigured(accountBucket, state.clusterPrefix()))

        // Configure per-cluster data bucket
        s3BucketService.configureDataBucket(
            bucketName = state.dataBucketName(),
            clusterId = state.clusterId,
            clusterName = state.name,
            metricsConfigId = state.metricsConfigId(),
        )

        state.dataBucket = state.dataBucketName()
        clusterStateManager.save(state)
    }

    /**
     * The account bucket [configureBuckets] set. That method is responsible for ensuring one
     * exists; this is the assertion that it did, so a bug there fails loudly here rather than
     * silently skipping registry TLS configuration and kubeconfig backup later.
     */
    private fun requireBucket(state: ClusterState): String {
        val bucket = state.s3Bucket
        if (bucket.isNullOrBlank()) {
            eventBus.emit(Event.Provision.S3BucketRequired(state.name))
            error("An S3 bucket is required to provision a cluster.")
        }
        return bucket
    }

    /**
     * Re-applies the S3Access inline policy and the account bucket policy, so existing roles and
     * the bucket carry the latest permissions and delete denies. Both are idempotent: PutRolePolicy
     * and PutBucketPolicy overwrite what is there.
     */
    private fun reapplyPolicies(bucket: String) {
        eventBus.emit(Event.Provision.IamUpdating)
        s3BucketService.attachS3Policy(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        s3BucketService.putBucketPolicy(bucket)
        log.debug { "S3 policy re-applied successfully" }
    }
}
