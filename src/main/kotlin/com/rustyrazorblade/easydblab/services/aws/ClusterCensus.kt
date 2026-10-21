package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.Filter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * One cluster VPC that names the account bucket.
 *
 * @property region where it runs.
 * @property vpcId its ID.
 */
data class ClusterVpc(
    val region: String,
    val vpcId: String,
)

/**
 * The census could not list the cluster VPCs in [region], so it cannot say whether any cluster
 * still uses the account bucket.
 */
class ClusterCensusException(
    val region: String,
    cause: Throwable,
) : RuntimeException(
        "Could not count the clusters in region $region that use the account bucket: ${cause.message}",
        cause,
    )

/**
 * Counts the clusters that use the account bucket: every VPC tagged `easy_cass_lab=1` whose
 * `bucket` tag names it, in every enabled region. The compactor's own VPC carries no `bucket` tag,
 * so it is never counted.
 */
class ClusterCensus(
    private val ec2: Ec2Client,
    private val regionalClients: RegionalClients,
) {
    /**
     * Every cluster VPC in any enabled region whose `bucket` tag is [bucket]; the regions are asked
     * at once. Each region's listing retries an EC2 server error like every other EC2 describe call
     * (`withAwsRetry`); a connection failure gets only the SDK client's own retries.
     *
     * @throws ClusterCensusException naming the first region whose listing still failed.
     */
    fun clusterVpcs(bucket: String): List<ClusterVpc> =
        ec2
            .describeRegions()
            .regions()
            .map { region -> CompletableFuture.supplyAsync { clusterVpcsIn(region.regionName(), bucket) } }
            // Every region's call has started before the first join waits.
            .flatMap { it.joinUnwrapped() }

    /** The future's value, or the failure its region raised rather than the [CompletionException] around it. */
    private fun <T> CompletableFuture<T>.joinUnwrapped(): T =
        try {
            join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

    private fun clusterVpcsIn(
        region: String,
        bucket: String,
    ): List<ClusterVpc> =
        try {
            withAwsRetry("census-describe-vpcs-$region") { describeClusterVpcs(region, bucket) }
        } catch (e: SdkException) {
            throw ClusterCensusException(region, e)
        }

    private fun describeClusterVpcs(
        region: String,
        bucket: String,
    ): List<ClusterVpc> =
        regionalClients.ec2(region).use { client ->
            client
                .describeVpcsPaginator(
                    DescribeVpcsRequest
                        .builder()
                        .filters(
                            Filter
                                .builder()
                                .name("tag:${Constants.Vpc.TAG_KEY}")
                                .values(Constants.Vpc.TAG_VALUE)
                                .build(),
                            Filter
                                .builder()
                                .name("tag:${Constants.Vpc.BUCKET_TAG_KEY}")
                                .values(bucket)
                                .build(),
                        ).build(),
                ).vpcs()
                .map { ClusterVpc(region, it.vpcId()) }
        }
}

/**
 * Whether `down` stops the account compactor: only when no cluster VPC is left that uses the
 * account bucket, once the VPCs this `down` tore down are left out.
 */
object CompactorShutdownPolicy {
    /** The decision, with the cluster VPCs that keep the compactor running. */
    sealed interface Decision {
        data object Stop : Decision

        data class Keep(
            val remaining: List<ClusterVpc>,
        ) : Decision
    }

    fun decide(
        census: List<ClusterVpc>,
        tornDown: Set<String>,
    ): Decision {
        val remaining = census.filterNot { it.vpcId in tornDown }
        return if (remaining.isEmpty()) Decision.Stop else Decision.Keep(remaining)
    }
}
