package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.Filter

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
 * Counts the clusters that use the account bucket: every VPC tagged `easy_cass_lab=1` whose
 * `bucket` tag names it, in every enabled region. The compactor's own VPC carries no `bucket` tag,
 * so it is never counted.
 */
class ClusterCensus(
    private val ec2: Ec2Client,
    private val regionalClients: RegionalClients,
) {
    /** Every cluster VPC in any enabled region whose `bucket` tag is [bucket]. */
    fun clusterVpcs(bucket: String): List<ClusterVpc> =
        ec2.describeRegions().regions().map { it.regionName() }.flatMap { region ->
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
