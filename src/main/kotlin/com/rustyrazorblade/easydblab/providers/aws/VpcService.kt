package com.rustyrazorblade.easydblab.providers.aws

/**
 * Service for managing AWS VPC infrastructure resources.
 *
 * This interface provides operations for creating and managing VPC components
 * including VPCs, subnets, internet gateways, security groups, and routing.
 * All operations are idempotent - they will find existing resources by name tag
 * or create them if they don't exist.
 */
interface VpcService {
    /**
     * Creates a new VPC.
     *
     * Note: This method always creates a new VPC. To use an existing VPC,
     * pass its ID directly via the --vpc flag during init.
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
     * Finds an existing security group by name or creates a new one.
     *
     * @param vpcId The VPC ID where the security group should be created
     * @param name The name to use for the security group (applied as Name tag and group name)
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
     * Ensures the route table for the subnet has a default route to the internet gateway.
     * Uses the VPC's main route table and associates it with the subnet.
     * Also applies tags to the route table for resource tracking.
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
     * Adds an ingress rule to a security group if it doesn't already exist.
     *
     * @param securityGroupId The security group ID
     * @param fromPort The starting port of the range (e.g., 22 for SSH)
     * @param toPort The ending port of the range (same as fromPort for single port)
     * @param cidr The CIDR block to allow traffic from (e.g., "0.0.0.0/0")
     * @param protocol The IP protocol (default: "tcp")
     */
    fun authorizeSecurityGroupIngress(
        securityGroupId: SecurityGroupId,
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String = "tcp",
    )

    // ==================== Discovery Methods ====================

    /**
     * Finds all VPCs with the specified tag.
     *
     * @param tagKey The tag key to search for
     * @param tagValue The tag value to match
     * @return List of VPC IDs matching the tag
     */
    fun findVpcsByTag(
        tagKey: String,
        tagValue: String,
    ): List<VpcId>

    /**
     * Finds a VPC by its Name tag.
     *
     * @param name The Name tag value to search for
     * @return The VPC ID if found, null otherwise
     */
    fun findVpcByName(name: ResourceName): VpcId?

    /**
     * Gets the Name tag value for a VPC.
     *
     * @param vpcId The VPC ID
     * @return The Name tag value if present, null otherwise
     */
    fun getVpcName(vpcId: VpcId): String?

    /**
     * Gets all tags on a VPC.
     *
     * @param vpcId The VPC ID
     * @return Map of tag key-value pairs on the VPC
     * @throws IllegalStateException if the VPC does not exist
     */
    fun getVpcTags(vpcId: VpcId): Map<String, String>

    /**
     * Adds or updates tags on a VPC.
     *
     * @param vpcId The VPC ID to tag
     * @param tags Map of tag key-value pairs to apply
     */
    fun addTagsToVpc(
        vpcId: VpcId,
        tags: Map<String, String>,
    )

    /**
     * Finds all EC2 instances in a VPC.
     *
     * @param vpcId The VPC ID to search in
     * @return List of instance IDs in the VPC
     */
    fun findInstancesInVpc(vpcId: VpcId): List<InstanceId>

    /**
     * Finds all subnets in a VPC.
     *
     * @param vpcId The VPC ID to search in
     * @return List of subnet IDs in the VPC
     */
    fun findSubnetsInVpc(vpcId: VpcId): List<SubnetId>

    /**
     * Finds all security groups in a VPC (excluding the default security group).
     *
     * @param vpcId The VPC ID to search in
     * @return List of security group IDs in the VPC
     */
    fun findSecurityGroupsInVpc(vpcId: VpcId): List<SecurityGroupId>

    /**
     * Finds all NAT gateways in a VPC.
     *
     * @param vpcId The VPC ID to search in
     * @return List of NAT gateway IDs in the VPC
     */
    fun findNatGatewaysInVpc(vpcId: VpcId): List<NatGatewayId>

    /**
     * Finds the internet gateway attached to a VPC.
     *
     * @param vpcId The VPC ID to search for
     * @return The internet gateway ID if found, null otherwise
     */
    fun findInternetGatewayByVpc(vpcId: VpcId): InternetGatewayId?

    /**
     * Finds all non-main route tables in a VPC.
     *
     * @param vpcId The VPC ID to search in
     * @return List of route table IDs in the VPC (excluding the main route table)
     */
    fun findRouteTablesInVpc(vpcId: VpcId): List<RouteTableId>

    // ==================== ENI Methods ====================

    /**
     * Finds active network interfaces in a VPC.
     *
     * Returns ENIs in in-use, attaching, or detaching state. These must be
     * cleared before subnets and security groups can be deleted.
     *
     * @param vpcId The VPC ID to search in
     * @return List of active network interface IDs
     */
    fun findActiveNetworkInterfacesInVpc(vpcId: VpcId): List<NetworkInterfaceId>

    /**
     * Waits for all active network interfaces in a VPC to be cleared.
     *
     * Polls until no ENIs are in in-use, attaching, or detaching state.
     * Returns immediately if no active ENIs are found.
     *
     * @param vpcId The VPC ID to monitor
     * @param timeoutMs Maximum time to wait in milliseconds
     * @throws AwsTimeoutException if ENIs are not cleared within the timeout
     */
    fun waitForNetworkInterfacesCleared(
        vpcId: VpcId,
        timeoutMs: Long = DEFAULT_ENI_TIMEOUT_MS,
    )

    // ==================== Deletion Methods ====================

    /**
     * Terminates EC2 instances.
     *
     * @param instanceIds List of instance IDs to terminate
     */
    fun terminateInstances(instanceIds: List<InstanceId>)

    /**
     * Waits for EC2 instances to reach terminated state.
     *
     * @param instanceIds List of instance IDs to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    fun waitForInstancesTerminated(
        instanceIds: List<InstanceId>,
        timeoutMs: Long = DEFAULT_TERMINATION_TIMEOUT_MS,
    )

    /**
     * Deletes a security group.
     *
     * @param securityGroupId The security group ID to delete
     */
    fun deleteSecurityGroup(securityGroupId: SecurityGroupId)

    /**
     * Revokes all ingress and egress rules from a security group.
     * This is used to break circular dependencies before deletion.
     *
     * @param securityGroupId The security group ID to revoke rules from
     */
    fun revokeSecurityGroupRules(securityGroupId: SecurityGroupId)

    /**
     * Detaches an internet gateway from a VPC.
     *
     * @param igwId The internet gateway ID
     * @param vpcId The VPC ID to detach from
     */
    fun detachInternetGateway(
        igwId: InternetGatewayId,
        vpcId: VpcId,
    )

    /**
     * Deletes an internet gateway.
     *
     * @param igwId The internet gateway ID to delete
     */
    fun deleteInternetGateway(igwId: InternetGatewayId)

    /**
     * Deletes a subnet.
     *
     * @param subnetId The subnet ID to delete
     */
    fun deleteSubnet(subnetId: SubnetId)

    /**
     * Deletes a NAT gateway.
     *
     * @param natGatewayId The NAT gateway ID to delete
     */
    fun deleteNatGateway(natGatewayId: NatGatewayId)

    /**
     * Waits for NAT gateways to be deleted.
     *
     * @param natGatewayIds List of NAT gateway IDs to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    fun waitForNatGatewaysDeleted(
        natGatewayIds: List<NatGatewayId>,
        timeoutMs: Long = DEFAULT_TERMINATION_TIMEOUT_MS,
    )

    /**
     * Deletes a route table.
     *
     * @param routeTableId The route table ID to delete
     */
    fun deleteRouteTable(routeTableId: RouteTableId)

    /**
     * Deletes a VPC.
     *
     * @param vpcId The VPC ID to delete
     */
    fun deleteVpc(vpcId: VpcId)

    // ==================== Security Group Details ====================

    /**
     * Get detailed information about a security group.
     *
     * @param securityGroupId The security group ID
     * @return SecurityGroupDetails
     * @throws IllegalStateException if the security group is not found
     */
    fun describeSecurityGroup(securityGroupId: String): SecurityGroupDetails

    companion object {
        /** Default timeout for waiting on resource termination/deletion (10 minutes) */
        const val DEFAULT_TERMINATION_TIMEOUT_MS = 10 * 60 * 1000L

        /** Default timeout for waiting on ENIs to clear (5 minutes) */
        const val DEFAULT_ENI_TIMEOUT_MS = 5 * 60 * 1000L

        /** Polling interval for checking resource state */
        const val POLL_INTERVAL_MS = 5000L
    }
}
