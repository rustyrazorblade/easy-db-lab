package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for reconstructing cluster state from AWS resources.
 *
 * When a user has an existing easy-db-lab VPC but lost their local state.json,
 * this service discovers resources in AWS and rebuilds the state file.
 */
interface StateReconstructionService {
    /**
     * Reconstructs ClusterState from resources associated with a VPC.
     *
     * The VPC must have a "ClusterId" tag (applied during init) which is used
     * to discover associated EC2 instances and S3 bucket.
     *
     * @param vpcId The VPC ID to reconstruct state from
     * @return A ClusterState populated with discovered resources
     * @throws IllegalStateException if VPC is missing the ClusterId tag
     */
    fun reconstructFromVpc(vpcId: VpcId): ClusterState
}

/**
 * Default implementation of StateReconstructionService.
 *
 * Reconstruction process:
 * 1. Get VPC tags to extract ClusterId and Name
 * 2. Use ClusterId to discover EC2 instances via existing findInstancesByClusterId
 * 3. Read the S3 bucket name from the VPC's 'bucket' tag
 * 4. Discover infrastructure resources (subnets, security groups, internet gateway)
 * 5. Build and return ClusterState
 */
class DefaultStateReconstructionService(
    private val vpcService: VpcService,
    private val ec2InstanceService: EC2InstanceService,
) : StateReconstructionService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val DEFAULT_CLUSTER_NAME = "recovered-cluster"
        private const val CLUSTER_ID_TAG_KEY = "ClusterId"
    }

    override fun reconstructFromVpc(vpcId: VpcId): ClusterState {
        log.info { "Reconstructing cluster state from VPC: $vpcId" }

        // 1. Get VPC tags to extract ClusterId and Name
        val vpcTags = vpcService.getVpcTags(vpcId)
        val clusterId =
            vpcTags[CLUSTER_ID_TAG_KEY]
                ?: error("VPC $vpcId is missing the ClusterId tag. This VPC was not created by easy-db-lab.")
        val clusterName = vpcTags["Name"] ?: DEFAULT_CLUSTER_NAME

        log.info { "Found ClusterId: $clusterId, Name: $clusterName" }

        // 2. Discover instances using existing method
        val instancesByType = ec2InstanceService.findInstancesByClusterId(clusterId)
        val hosts =
            instancesByType.mapValues { (_, instances) ->
                instances.map { it.toClusterHost() }
            }
        log.info { "Discovered ${hosts.values.sumOf { it.size }} instances" }

        // 3. Discover infrastructure
        val subnetIds = vpcService.findSubnetsInVpc(vpcId)
        val securityGroupIds = vpcService.findSecurityGroupsInVpc(vpcId)
        val igwId = vpcService.findInternetGatewayByVpc(vpcId)

        val infrastructure =
            InfrastructureState(
                vpcId = vpcId,
                subnetIds = subnetIds,
                securityGroupId = securityGroupIds.firstOrNull(),
                internetGatewayId = igwId,
            )
        log.info { "Discovered infrastructure: ${subnetIds.size} subnets, ${securityGroupIds.size} security groups" }

        // 4. Get S3 bucket from VPC tag
        val s3Bucket = vpcTags[Constants.Vpc.BUCKET_TAG_KEY]
        if (s3Bucket != null) {
            log.info { "Found S3 bucket from VPC tag: $s3Bucket" }
        } else {
            log.warn { "VPC $vpcId has no '${Constants.Vpc.BUCKET_TAG_KEY}' tag — S3 backup/restore will be unavailable" }
        }

        // 5. Build ClusterState
        return ClusterState(
            name = clusterName,
            clusterId = clusterId,
            vpcId = vpcId,
            hosts = hosts,
            infrastructure = infrastructure,
            s3Bucket = s3Bucket,
            infrastructureStatus = InfrastructureStatus.UP,
            versions = null,
        )
    }
}
