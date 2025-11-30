package com.rustyrazorblade.easycasslab.providers.aws

/**
 * Represents the core VPC infrastructure components.
 *
 * This data class holds the AWS resource IDs for a complete VPC setup
 * including networking and security components.
 *
 * @property vpcId The VPC ID
 * @property subnetIds List of subnet IDs within the VPC (supports multi-AZ deployments)
 * @property securityGroupId The security group ID for instance access
 * @property internetGatewayId The internet gateway ID for external connectivity
 */
data class VpcInfrastructure(
    val vpcId: VpcId,
    val subnetIds: List<SubnetId>,
    val securityGroupId: SecurityGroupId,
    val internetGatewayId: InternetGatewayId,
) {
    /**
     * Returns the first subnet ID for backward compatibility with single-subnet use cases.
     */
    val subnetId: SubnetId
        get() = subnetIds.first()

    companion object {
        /**
         * Creates a VpcInfrastructure with a single subnet.
         * Useful for simple setups like packer builds.
         */
        fun withSingleSubnet(
            vpcId: VpcId,
            subnetId: SubnetId,
            securityGroupId: SecurityGroupId,
            internetGatewayId: InternetGatewayId,
        ): VpcInfrastructure =
            VpcInfrastructure(
                vpcId = vpcId,
                subnetIds = listOf(subnetId),
                securityGroupId = securityGroupId,
                internetGatewayId = internetGatewayId,
            )
    }
}
