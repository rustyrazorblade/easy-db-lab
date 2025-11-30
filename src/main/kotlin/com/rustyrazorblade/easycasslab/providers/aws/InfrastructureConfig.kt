package com.rustyrazorblade.easycasslab.providers.aws

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
        private const val TAG_KEY = "easy_cass_lab"
        private const val TAG_VALUE = "1"
        private const val VPC_CIDR = "10.0.0.0/16"

        /** VPC name for packer infrastructure - used for discovery and teardown */
        const val PACKER_VPC_NAME = "easy-cass-lab-packer"

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
                vpcCidr = VPC_CIDR,
                subnets =
                    listOf(
                        SubnetConfig(
                            name = "easy-cass-lab-packer-subnet",
                            cidr = "10.0.1.0/24",
                        ),
                    ),
                securityGroupName = "easy-cass-lab-packer-sg",
                securityGroupDescription = "Security group for Packer AMI builds",
                securityGroupRules =
                    listOf(
                        SecurityGroupRule.singlePort(sshPort, "0.0.0.0/0"),
                    ),
                internetGatewayName = "easy-cass-lab-packer-igw",
                tags = mapOf(TAG_KEY to TAG_VALUE),
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
         */
        fun forCluster(
            clusterName: String,
            availabilityZones: List<String>,
            sshCidrs: List<String>,
            sshPort: Int,
        ): InfrastructureConfig {
            val subnets =
                availabilityZones.mapIndexed { index, az ->
                    SubnetConfig(
                        name = "easy-cass-lab-$clusterName-subnet-$index",
                        cidr = "10.0.${index + 1}.0/24",
                        availabilityZone = az,
                    )
                }

            val rules = mutableListOf<SecurityGroupRule>()
            // SSH access from specified CIDRs
            sshCidrs.forEach { cidr ->
                rules.add(SecurityGroupRule.singlePort(sshPort, cidr))
            }
            // Internal VPC traffic (all ports within VPC CIDR)
            rules.add(SecurityGroupRule(0, 65535, VPC_CIDR, "tcp"))
            rules.add(SecurityGroupRule(0, 65535, VPC_CIDR, "udp"))

            return InfrastructureConfig(
                vpcName = "easy-cass-lab-$clusterName",
                vpcCidr = VPC_CIDR,
                subnets = subnets,
                securityGroupName = "easy-cass-lab-$clusterName-sg",
                securityGroupDescription = "Security group for easy-cass-lab cluster: $clusterName",
                securityGroupRules = rules,
                internetGatewayName = "easy-cass-lab-$clusterName-igw",
                tags = mapOf(TAG_KEY to TAG_VALUE),
            )
        }
    }
}
