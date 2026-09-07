package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Resolves the region the account-level S3 bucket lives in, once per cluster.
 *
 * The account bucket is one per account, while a cluster can be brought up in any region. Anything
 * that builds an S3 endpoint for that bucket — Pyroscope's object store, for one — therefore needs
 * the bucket's region and not the cluster's. Deriving it from the cluster works whenever the two
 * happen to match and points at the wrong endpoint whenever they do not, which is why no fallback
 * to the cluster's region exists here.
 *
 * Every caller goes through here, `up` included: it saves the bucket into `state.json` and then
 * calls [resolve], so there is one resolution path, one failure message and one log line. A
 * `state.json` written before the field existed carries no region, which this service resolves on
 * first use and persists: the `GetBucketLocation` call happens once per cluster, not once per
 * command, and the user never has to re-provision.
 *
 * @property clusterStateManager Reads and persists the cluster's `state.json`
 * @property s3BucketService Performs the `GetBucketLocation` call
 */
class AccountBucketRegionService(
    private val clusterStateManager: ClusterStateManager,
    private val s3BucketService: AwsS3BucketService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Returns the account bucket's region, resolving and persisting it if it is not stored yet.
     *
     * @throws IllegalStateException if no account bucket is configured, or if `GetBucketLocation`
     *   fails. Neither case substitutes another region.
     */
    fun resolve(): String {
        val state = clusterStateManager.load()
        state.accountBucketRegion?.takeIf { it.isNotBlank() }?.let { return it }

        val bucket =
            state.s3Bucket?.takeIf { it.isNotBlank() }
                ?: error(
                    "No account S3 bucket is configured for cluster '${state.name}', " +
                        "so its region cannot be resolved. Run 'easy-db-lab up' first.",
                )

        val region =
            try {
                s3BucketService.getBucketRegion(bucket)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to resolve the region of account S3 bucket '$bucket' " +
                        "via GetBucketLocation. Check that the bucket exists and that the current " +
                        "credential is allowed to call GetBucketLocation on it.",
                    e,
                )
            }

        state.accountBucketRegion = region
        clusterStateManager.save(state)
        log.info { "Resolved region of account S3 bucket $bucket as $region" }
        return region
    }
}
