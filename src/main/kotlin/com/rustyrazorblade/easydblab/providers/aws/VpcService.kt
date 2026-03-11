package com.rustyrazorblade.easydblab.providers.aws

/**
 * Operations for creating and configuring VPC core resources.
 */
interface VpcCoreOperations {
    /**
     * Creates a new VPC.
     *
     * @param name The name to use for the VPC (applied as Name tag)
     * @param cidr The CIDR block for the VPC (e.g., "10.0.0.0/16")
     * @param tags Additional tags to apply to the VPC
     * @return The VPC ID
     */
    fun createVpc(
        name: ResourceName,
        cidr: Cidr,
        tags: Map<String, String>,
    ): VpcId

    /**
     * Finds an existing subnet by name tag or creates a new one.
     *
     * @param vpcId The VPC ID where the subnet should be created
     * @param name The name to use for the subnet (applied as Name tag)
     * @param cidr The CIDR block for the subnet (e.g., "10.0.1.0/24")
     * @param tags Additional tags to apply to the subnet
     * @param availabilityZone Optional availability zone for the subnet
     * @return The subnet ID
     */
    fun findOrCreateSubnet(
        vpcId: VpcId,
        name: ResourceName,
        cidr: Cidr,
        tags: Map<String, String>,
        availabilityZone: String? = null,
    ): SubnetId

    /**
     * Finds an existing internet gateway by name tag or creates a new one,
     * and ensures it is attached to the specified VPC.
     *
     * @param vpcId The VPC ID to attach the internet gateway to
     * @param name The name to use for the internet gateway (applied as Name tag)
     * @param tags Additional tags to apply to the internet gateway
     * @return The internet gateway ID
     */
    fun findOrCreateInternetGateway(
        vpcId: VpcId,
        name: ResourceName,
        tags: Map<String, String>,
    ): InternetGatewayId

    /**
     * Ensures the route table for the subnet has a default route to the internet gateway.
     *
     * @param vpcId The VPC ID
     * @param subnetId The subnet ID to associate with the route table
     * @param igwId The internet gateway ID to route traffic to
     * @param tags Tags to apply to the route table (optional)
     */
    fun ensureRouteTable(
        vpcId: VpcId,
        subnetId: SubnetId,
        igwId: InternetGatewayId,
        tags: Map<String, String> = emptyMap(),
    )

    /**
     * Deletes a VPC.
     *
     * @param vpcId The VPC ID to delete
     */
    fun deleteVpc(vpcId: VpcId)
}

/**
 * Operations for managing security groups.
 */
interface SecurityGroupOperations {
    /**
     * Finds an existing security group by name or creates a new one.
     *
     * @param vpcId The VPC ID where the security group should be created
     * @param name The name to use for the security group
     * @param description Description for the security group
     * @param tags Additional tags to apply to the security group
     * @return The security group ID
     */
    fun findOrCreateSecurityGroup(
        vpcId: VpcId,
        name: ResourceName,
        description: ResourceDescription,
        tags: Map<String, String>,
    ): SecurityGroupId

    /**
     * Adds an ingress rule to a security group if it doesn't already exist.
     *
     * @param securityGroupId The security group ID
     * @param fromPort The starting port of the range
     * @param toPort The ending port of the range
     * @param cidr The CIDR block to allow traffic from
     * @param protocol The IP protocol (default: "tcp")
     */
    fun authorizeSecurityGroupIngress(
        securityGroupId: SecurityGroupId,
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String = "tcp",
    )

    /**
     * Deletes a security group.
     *
     * @param securityGroupId The security group ID to delete
     */
    fun deleteSecurityGroup(securityGroupId: SecurityGroupId)

    /**
     * Revokes all ingress and egress rules from a security group.
     *
     * @param securityGroupId The security group ID to revoke rules from
     */
    fun revokeSecurityGroupRules(securityGroupId: SecurityGroupId)

    /**
     * Get detailed information about a security group.
     *
     * @param securityGroupId The security group ID
     * @return SecurityGroupDetails
     * @throws IllegalStateException if the security group is not found
     */
    fun describeSecurityGroup(securityGroupId: String): SecurityGroupDetails
}

/**
 * Operations for discovering VPC resources.
 */
interface VpcDiscoveryOperations {
    /**
     * Finds all VPCs with the specified tag.
     */
    fun findVpcsByTag(
        tagKey: String,
        tagValue: String,
    ): List<VpcId>

    /**
     * Finds a VPC by its Name tag.
     */
    fun findVpcByName(name: ResourceName): VpcId?

    /**
     * Gets the Name tag value for a VPC.
     */
    fun getVpcName(vpcId: VpcId): String?

    /**
     * Gets all tags on a VPC.
     */
    fun getVpcTags(vpcId: VpcId): Map<String, String>

    /**
     * Adds or updates tags on a VPC.
     */
    fun addTagsToVpc(
        vpcId: VpcId,
        tags: Map<String, String>,
    )

    /**
     * Finds all EC2 instances in a VPC.
     */
    fun findInstancesInVpc(vpcId: VpcId): List<InstanceId>

    /**
     * Finds all subnets in a VPC.
     */
    fun findSubnetsInVpc(vpcId: VpcId): List<SubnetId>

    /**
     * Finds all security groups in a VPC (excluding the default security group).
     */
    fun findSecurityGroupsInVpc(vpcId: VpcId): List<SecurityGroupId>

    /**
     * Finds all NAT gateways in a VPC.
     */
    fun findNatGatewaysInVpc(vpcId: VpcId): List<NatGatewayId>

    /**
     * Finds the internet gateway attached to a VPC.
     */
    fun findInternetGatewayByVpc(vpcId: VpcId): InternetGatewayId?

    /**
     * Finds all non-main route tables in a VPC.
     */
    fun findRouteTablesInVpc(vpcId: VpcId): List<RouteTableId>

    /**
     * Finds active network interfaces in a VPC.
     */
    fun findActiveNetworkInterfacesInVpc(vpcId: VpcId): List<NetworkInterfaceId>
}

/**
 * Operations for tearing down VPC resources.
 */
interface VpcTeardownOperations {
    /**
     * Terminates EC2 instances.
     */
    fun terminateInstances(instanceIds: List<InstanceId>)

    /**
     * Waits for EC2 instances to reach terminated state.
     */
    fun waitForInstancesTerminated(
        instanceIds: List<InstanceId>,
        timeoutMs: Long = VpcService.DEFAULT_TERMINATION_TIMEOUT_MS,
    )

    /**
     * Waits for all active network interfaces in a VPC to be cleared.
     */
    fun waitForNetworkInterfacesCleared(
        vpcId: VpcId,
        timeoutMs: Long = VpcService.DEFAULT_ENI_TIMEOUT_MS,
    )

    /**
     * Detaches an internet gateway from a VPC.
     */
    fun detachInternetGateway(
        igwId: InternetGatewayId,
        vpcId: VpcId,
    )

    /**
     * Deletes an internet gateway.
     */
    fun deleteInternetGateway(igwId: InternetGatewayId)

    /**
     * Deletes a subnet.
     */
    fun deleteSubnet(subnetId: SubnetId)

    /**
     * Deletes a NAT gateway.
     */
    fun deleteNatGateway(natGatewayId: NatGatewayId)

    /**
     * Waits for NAT gateways to be deleted.
     */
    fun waitForNatGatewaysDeleted(
        natGatewayIds: List<NatGatewayId>,
        timeoutMs: Long = VpcService.DEFAULT_TERMINATION_TIMEOUT_MS,
    )

    /**
     * Deletes a route table.
     */
    fun deleteRouteTable(routeTableId: RouteTableId)
}

/**
 * Service for managing AWS VPC infrastructure resources.
 *
 * This interface provides operations for creating and managing VPC components
 * including VPCs, subnets, internet gateways, security groups, and routing.
 * All operations are idempotent - they will find existing resources by name tag
 * or create them if they don't exist.
 */
interface VpcService :
    VpcCoreOperations,
    SecurityGroupOperations,
    VpcDiscoveryOperations,
    VpcTeardownOperations {
    companion object {
        /** Default timeout for waiting on resource termination/deletion (10 minutes) */
        const val DEFAULT_TERMINATION_TIMEOUT_MS = 10 * 60 * 1000L

        /** Default timeout for waiting on ENIs to clear (5 minutes) */
        const val DEFAULT_ENI_TIMEOUT_MS = 5 * 60 * 1000L

        /** Polling interval for checking resource state */
        const val POLL_INTERVAL_MS = 5000L
    }
}
