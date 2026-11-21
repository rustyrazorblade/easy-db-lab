package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.containers.BuilderInstance
import com.rustyrazorblade.easydblab.containers.BuilderInstances
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest

/**
 * The EC2 adapter of [BuilderInstances]: the live instances of one AMI build, [buildId], in the
 * region [ec2Client] is bound to.
 *
 * An instance belongs to the build only when it is in the packer VPC that easy-db-lab created
 * ([VpcOwnership]), carries the legacy marker tag and carries this build's id
 * ([Constants.Packer.BUILD_ID_TAG_KEY]). A concurrent build's builder, or one an operator keeps for
 * debugging, carries another id and is never listed or terminated.
 */
class Ec2BuilderInstances(
    private val ec2Client: Ec2Client,
    private val buildId: String,
) : BuilderInstances {
    private val liveStates =
        listOf(InstanceStateName.PENDING, InstanceStateName.RUNNING, InstanceStateName.STOPPING, InstanceStateName.STOPPED)
            .map { it.toString() }

    override fun list(): List<BuilderInstance> {
        val vpcIds = packerVpcIds()
        if (vpcIds.isEmpty()) return emptyList()
        val request =
            DescribeInstancesRequest
                .builder()
                .filters(
                    filter("vpc-id", vpcIds),
                    filter("tag:${Constants.Packer.BUILD_ID_TAG_KEY}", listOf(buildId)),
                    filter("tag:${Constants.Vpc.LEGACY_TAG_KEY}", listOf(Constants.Vpc.TAG_VALUE)),
                    filter("instance-state-name", liveStates),
                ).build()
        return withAwsRetry("list-build-instances") {
            ec2Client
                .describeInstancesPaginator(request)
                .reservations()
                .flatMap { it.instances() }
                .map { BuilderInstance(it.instanceId(), it.publicIpAddress()) }
        }
    }

    override fun terminate(instanceIds: List<String>) {
        if (instanceIds.isEmpty()) return
        withAwsRetry("terminate-build-instances") {
            ec2Client.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceIds).build())
        }
    }

    /** The VPCs named like the packer VPC that easy-db-lab created; a same-named VPC of anyone else's is ignored. */
    private fun packerVpcIds(): List<String> {
        val request =
            DescribeVpcsRequest
                .builder()
                .filters(
                    filter("tag:${Constants.Vpc.NAME_TAG_KEY}", listOf(Constants.Vpc.PACKER_VPC_NAME)),
                ).build()
        return withAwsRetry("find-packer-vpc") { ec2Client.describeVpcs(request).vpcs() }
            .filter { vpc -> VpcOwnership.createdByEasyDbLab(vpc.tags().associate { it.key() to it.value() }) }
            .map { it.vpcId() }
    }

    private fun filter(
        name: String,
        values: List<String>,
    ): Filter =
        Filter
            .builder()
            .name(name)
            .values(values)
            .build()
}
