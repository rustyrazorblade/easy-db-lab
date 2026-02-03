package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.CidrBlock

/**
 * Configuration for setting up networking within an existing VPC.
 *
 * Used when the VPC already exists (e.g., created by terraform or init-config)
 * and we need to set up subnets, security groups, and routing.
 *
 * @property vpcId The ID of the existing VPC
 * @property clusterName Name of the cluster (used for resource naming)
 * @property clusterId Unique cluster ID (used for tagging)
 * @property region AWS region (used to construct full AZ names)
 * @property availabilityZones List of availability zone suffixes (e.g., ["a", "b", "c"])
 * @property isOpen Whether the cluster allows SSH from anywhere (0.0.0.0/0) or just the user's IP
 * @property tags Additional tags to apply to resources
 */
data class VpcNetworkingConfig(
    val vpcId: String,
    val clusterName: String,
    val clusterId: String,
    val region: String,
    val availabilityZones: List<String>,
    val isOpen: Boolean,
    val tags: Map<String, String> = emptyMap(),
    val vpcCidr: String = Constants.Vpc.DEFAULT_CIDR,
)

/**
 * Configuration for a subnet to be created within a VPC.
 *
 * @property name Name tag for the subnet
 * @property cidr CIDR block for the subnet (e.g., "10.0.1.0/24")
 * @property availabilityZone Optional availability zone for the subnet
 */
data class SubnetConfig(
    val name: String,
    val cidr: String,
    val availabilityZone: String? = null,
)

/**
 * Configuration for a security group ingress rule.
 *
 * @property fromPort Starting port of the range
 * @property toPort Ending port of the range (same as fromPort for single port)
 * @property cidr CIDR block to allow traffic from
 * @property protocol IP protocol (default: "tcp")
 */
data class SecurityGroupRule(
    val fromPort: Int,
    val toPort: Int,
    val cidr: String,
    val protocol: String = "tcp",
) {
    companion object {
        /**
         * Creates a rule for a single port.
         */
        fun singlePort(
            port: Int,
            cidr: String,
            protocol: String = "tcp",
        ) = SecurityGroupRule(port, port, cidr, protocol)
    }
}

/**
 * Configuration for AWS VPC infrastructure creation.
 *
 * This configuration class supports both simple single-subnet setups (for packer builds)
 * and multi-subnet setups (for cluster deployments with multiple availability zones).
 *
 * @property vpcName Name tag for the VPC
 * @property vpcCidr CIDR block for the VPC (e.g., "10.0.0.0/16")
 * @property subnets List of subnet configurations
 * @property securityGroupName Name for the security group
 * @property securityGroupDescription Description for the security group
 * @property securityGroupRules Ingress rules for the security group
 * @property internetGatewayName Name for the internet gateway
 * @property tags Additional tags to apply to all resources
 */
data class InfrastructureConfig(
    val vpcName: String,
    val vpcCidr: String,
    val subnets: List<SubnetConfig>,
    val securityGroupName: String,
    val securityGroupDescription: String,
    val securityGroupRules: List<SecurityGroupRule>,
    val internetGatewayName: String,
    val tags: Map<String, String> = emptyMap(),
) {
    companion object {
        /** VPC name for packer infrastructure - used for discovery and teardown */
        val PACKER_VPC_NAME = Constants.Vpc.PACKER_VPC_NAME

        /**
         * Creates configuration for Packer AMI build infrastructure.
         *
         * Packer needs:
         * - Single subnet in any AZ
         * - SSH access from anywhere (0.0.0.0/0)
         */
        fun forPacker(sshPort: Int): InfrastructureConfig =
            InfrastructureConfig(
                vpcName = PACKER_VPC_NAME,
                vpcCidr = Constants.Vpc.DEFAULT_CIDR,
                subnets =
                    listOf(
                        SubnetConfig(
                            name = "easy-db-lab-packer-subnet",
                            cidr = Constants.Vpc.subnetCidr(0),
                        ),
                    ),
                securityGroupName = "easy-db-lab-packer-sg",
                securityGroupDescription = "Security group for Packer AMI builds",
                securityGroupRules =
                    listOf(
                        SecurityGroupRule.singlePort(sshPort, "0.0.0.0/0"),
                    ),
                internetGatewayName = "easy-db-lab-packer-igw",
                tags = mapOf(Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE),
            )

        /**
         * Creates configuration for cluster infrastructure.
         *
         * Clusters need:
         * - Multiple subnets (one per availability zone)
         * - SSH access from specified CIDRs
         * - Internal VPC traffic for Cassandra communication
         *
         * @param clusterName Name of the cluster
         * @param availabilityZones List of availability zones to create subnets in
         * @param sshCidrs CIDRs allowed to SSH into the cluster
         * @param sshPort SSH port number
         * @param vpcCidr VPC CIDR block for subnet calculation and security group rules
         */
        fun forCluster(
            clusterName: String,
            availabilityZones: List<String>,
            sshCidrs: List<String>,
            sshPort: Int,
            vpcCidr: String = Constants.Vpc.DEFAULT_CIDR,
        ): InfrastructureConfig {
            val cidrBlock = CidrBlock(vpcCidr)
            val subnets =
                availabilityZones.mapIndexed { index, az ->
                    SubnetConfig(
                        name = "easy-db-lab-$clusterName-subnet-$index",
                        cidr = cidrBlock.subnetCidr(index),
                        availabilityZone = az,
                    )
                }

            val rules = mutableListOf<SecurityGroupRule>()
            // SSH access from specified CIDRs
            sshCidrs.forEach { cidr ->
                rules.add(SecurityGroupRule.singlePort(sshPort, cidr))
            }
            // Internal VPC traffic (all ports within VPC CIDR)
            rules.add(
                SecurityGroupRule(
                    Constants.Network.MIN_PORT,
                    Constants.Network.MAX_PORT,
                    vpcCidr,
                    "tcp",
                ),
            )
            rules.add(
                SecurityGroupRule(
                    Constants.Network.MIN_PORT,
                    Constants.Network.MAX_PORT,
                    vpcCidr,
                    "udp",
                ),
            )

            return InfrastructureConfig(
                vpcName = "easy-db-lab-$clusterName",
                vpcCidr = vpcCidr,
                subnets = subnets,
                securityGroupName = "easy-db-lab-$clusterName-sg",
                securityGroupDescription = "Security group for easy-db-lab cluster: $clusterName",
                securityGroupRules = rules,
                internetGatewayName = "easy-db-lab-$clusterName-igw",
                tags = mapOf(Constants.Vpc.TAG_KEY to Constants.Vpc.TAG_VALUE),
            )
        }
    }
}
