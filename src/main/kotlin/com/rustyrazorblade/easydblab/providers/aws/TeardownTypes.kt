package com.rustyrazorblade.easydblab.providers.aws

/**
 * Represents all AWS resources discovered within a VPC that may need to be deleted.
 *
 * This class is used both for dry-run previews and for tracking actual deletions.
 * Resources are organized by type to support the correct deletion order.
 */
data class DiscoveredResources(
    val vpcId: VpcId,
    val vpcName: String? = null,
    val instanceIds: List<InstanceId> = emptyList(),
    val emrClusterIds: List<ClusterId> = emptyList(),
    val openSearchDomainNames: List<String> = emptyList(),
    val securityGroupIds: List<SecurityGroupId> = emptyList(),
    val subnetIds: List<SubnetId> = emptyList(),
    val internetGatewayId: InternetGatewayId? = null,
    val natGatewayIds: List<NatGatewayId> = emptyList(),
    val routeTableIds: List<RouteTableId> = emptyList(),
) {
    /**
     * Returns true if this VPC is the packer infrastructure VPC.
     */
    fun isPackerVpc(): Boolean = vpcName == InfrastructureConfig.PACKER_VPC_NAME

    /**
     * Returns true if there are any resources to delete.
     */
    fun hasResources(): Boolean =
        instanceIds.isNotEmpty() ||
            emrClusterIds.isNotEmpty() ||
            openSearchDomainNames.isNotEmpty() ||
            securityGroupIds.isNotEmpty() ||
            subnetIds.isNotEmpty() ||
            internetGatewayId != null ||
            natGatewayIds.isNotEmpty()

    /**
     * Returns a human-readable summary of discovered resources.
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts.add("VPC: $vpcId" + (vpcName?.let { " ($it)" } ?: ""))
        if (instanceIds.isNotEmpty()) parts.add("  EC2 Instances: ${instanceIds.size}")
        if (emrClusterIds.isNotEmpty()) parts.add("  EMR Clusters: ${emrClusterIds.size}")
        if (openSearchDomainNames.isNotEmpty()) parts.add("  OpenSearch Domains: ${openSearchDomainNames.size}")
        if (securityGroupIds.isNotEmpty()) parts.add("  Security Groups: ${securityGroupIds.size}")
        if (subnetIds.isNotEmpty()) parts.add("  Subnets: ${subnetIds.size}")
        if (natGatewayIds.isNotEmpty()) parts.add("  NAT Gateways: ${natGatewayIds.size}")
        if (internetGatewayId != null) parts.add("  Internet Gateway: 1")
        return parts.joinToString("\n")
    }
}

/**
 * Result of a teardown operation.
 *
 * Contains information about what was deleted and any errors encountered.
 */
data class TeardownResult(
    val success: Boolean,
    val resourcesDeleted: List<DiscoveredResources> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    companion object {
        /**
         * Creates a successful result with the given deleted resources.
         */
        fun success(resources: List<DiscoveredResources>): TeardownResult = TeardownResult(success = true, resourcesDeleted = resources)

        /**
         * Creates a successful result for a single VPC.
         */
        fun success(resources: DiscoveredResources): TeardownResult = TeardownResult(success = true, resourcesDeleted = listOf(resources))

        /**
         * Creates a failed result with the given errors.
         */
        fun failure(
            errors: List<String>,
            partialResources: List<DiscoveredResources> = emptyList(),
        ): TeardownResult = TeardownResult(success = false, resourcesDeleted = partialResources, errors = errors)

        /**
         * Creates a failed result with a single error message.
         */
        fun failure(error: String): TeardownResult = TeardownResult(success = false, errors = listOf(error))
    }

    /**
     * Returns a human-readable summary of the teardown result.
     */
    fun summary(): String {
        val sb = StringBuilder()
        if (success) {
            sb.appendLine("Teardown completed successfully.")
        } else {
            sb.appendLine("Teardown completed with errors.")
        }
        if (resourcesDeleted.isNotEmpty()) {
            sb.appendLine("Resources deleted:")
            resourcesDeleted.forEach { sb.appendLine(it.summary()) }
        }
        if (errors.isNotEmpty()) {
            sb.appendLine("Errors:")
            errors.forEach { sb.appendLine("  - $it") }
        }
        return sb.toString().trim()
    }
}

/**
 * Defines the mode of operation for the down command.
 */
sealed class TeardownMode {
    /**
     * Tear down the current cluster using cluster state.
     */
    data object CurrentCluster : TeardownMode()

    /**
     * Tear down a specific VPC by ID.
     */
    data class SpecificVpc(
        val vpcId: VpcId,
    ) : TeardownMode()

    /**
     * Tear down all VPCs tagged with easy_cass_lab.
     */
    data object AllTagged : TeardownMode()

    /**
     * Tear down the packer infrastructure VPC.
     */
    data object PackerInfrastructure : TeardownMode()
}
