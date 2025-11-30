package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for orchestrating AWS infrastructure teardown.
 *
 * This service coordinates the deletion of VPC infrastructure and associated resources
 * in the correct dependency order to ensure clean teardown without AWS dependency errors.
 *
 * ## Deletion Order
 * The following order is critical to avoid dependency conflicts:
 * 1. EMR Clusters - Must be terminated before VPC resources
 * 2. EC2 Instances - Must be terminated before security groups and subnets
 * 3. NAT Gateways - Must be deleted before subnets
 * 4. Security Groups - Must be deleted after instances
 * 5. Route Tables - Non-main route tables deleted before subnets
 * 6. Subnets - Must be deleted after all subnet resources
 * 7. Internet Gateway - Must be detached and deleted before VPC
 * 8. VPC - Deleted last after all dependencies removed
 */
@Suppress("TooManyFunctions")
class InfrastructureTeardownService(
    private val vpcService: VpcService,
    private val emrTeardownService: EMRTeardownService,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Tag key used to identify easy-cass-lab resources */
        const val TAG_KEY = "easy_cass_lab"

        /** Tag value used to identify easy-cass-lab resources */
        const val TAG_VALUE = "1"
    }

    /**
     * Discovers all resources in a VPC that will be deleted during teardown.
     *
     * @param vpcId The VPC ID to discover resources in
     * @return DiscoveredResources containing all resources found in the VPC
     */
    fun discoverResources(vpcId: VpcId): DiscoveredResources {
        log.info { "Discovering resources in VPC: $vpcId" }
        outputHandler.handleMessage("Discovering resources in VPC: $vpcId")

        val vpcName = vpcService.getVpcName(vpcId)
        val instanceIds = vpcService.findInstancesInVpc(vpcId)
        val subnetIds = vpcService.findSubnetsInVpc(vpcId)
        val securityGroupIds = vpcService.findSecurityGroupsInVpc(vpcId)
        val natGatewayIds = vpcService.findNatGatewaysInVpc(vpcId)
        val internetGatewayId = vpcService.findInternetGatewayByVpc(vpcId)
        val routeTableIds = vpcService.findRouteTablesInVpc(vpcId)
        val emrClusterIds = emrTeardownService.findClustersInVpc(vpcId, subnetIds)

        return DiscoveredResources(
            vpcId = vpcId,
            vpcName = vpcName,
            instanceIds = instanceIds,
            emrClusterIds = emrClusterIds,
            securityGroupIds = securityGroupIds,
            subnetIds = subnetIds,
            internetGatewayId = internetGatewayId,
            natGatewayIds = natGatewayIds,
            routeTableIds = routeTableIds,
        )
    }

    /**
     * Tears down a specific VPC and all its resources.
     *
     * @param vpcId The VPC ID to tear down
     * @param dryRun If true, only discover and report resources without deleting
     * @return TeardownResult with information about deleted resources or errors
     */
    fun teardownVpc(
        vpcId: VpcId,
        dryRun: Boolean = false,
    ): TeardownResult {
        log.info { "Starting teardown of VPC: $vpcId (dryRun=$dryRun)" }

        val resources = discoverResources(vpcId)

        if (dryRun) {
            outputHandler.handleMessage("\n=== DRY RUN - Resources that would be deleted ===")
            outputHandler.handleMessage(resources.summary())
            outputHandler.handleMessage("=== End DRY RUN ===\n")
            return TeardownResult.success(resources)
        }

        return executeVpcTeardown(resources)
    }

    /**
     * Tears down all VPCs tagged with easy_cass_lab.
     *
     * @param dryRun If true, only discover and report resources without deleting
     * @param includePackerVpc If true, include the packer infrastructure VPC
     * @return TeardownResult with information about deleted resources or errors
     */
    fun teardownAllTagged(
        dryRun: Boolean = false,
        includePackerVpc: Boolean = false,
    ): TeardownResult {
        log.info { "Starting teardown of all tagged VPCs (dryRun=$dryRun, includePackerVpc=$includePackerVpc)" }
        outputHandler.handleMessage("Finding all VPCs tagged with $TAG_KEY=$TAG_VALUE...")

        val vpcIds = vpcService.findVpcsByTag(TAG_KEY, TAG_VALUE)

        if (vpcIds.isEmpty()) {
            outputHandler.handleMessage("No VPCs found with tag $TAG_KEY=$TAG_VALUE")
            return TeardownResult.success(emptyList())
        }

        outputHandler.handleMessage("Found ${vpcIds.size} VPCs to tear down")

        val allResources = mutableListOf<DiscoveredResources>()
        val errors = mutableListOf<String>()

        for (vpcId in vpcIds) {
            val resources = discoverResources(vpcId)

            // Skip packer VPC unless explicitly included
            if (resources.isPackerVpc() && !includePackerVpc) {
                outputHandler.handleMessage("Skipping packer VPC: $vpcId (use --packer to include)")
                continue
            }

            allResources.add(resources)
        }

        if (dryRun) {
            outputHandler.handleMessage("\n=== DRY RUN - Resources that would be deleted ===")
            allResources.forEach { resources ->
                outputHandler.handleMessage(resources.summary())
                outputHandler.handleMessage("")
            }
            outputHandler.handleMessage("=== End DRY RUN ===\n")
            return TeardownResult.success(allResources)
        }

        // Execute teardown for each VPC
        for (resources in allResources) {
            val result = executeVpcTeardown(resources)
            if (!result.success) {
                errors.addAll(result.errors)
            }
        }

        return if (errors.isEmpty()) {
            TeardownResult.success(allResources)
        } else {
            TeardownResult.failure(errors, allResources)
        }
    }

    /**
     * Tears down the packer infrastructure VPC.
     *
     * @param dryRun If true, only discover and report resources without deleting
     * @return TeardownResult with information about deleted resources or errors
     */
    fun teardownPackerInfrastructure(dryRun: Boolean = false): TeardownResult {
        log.info { "Starting teardown of packer infrastructure (dryRun=$dryRun)" }
        outputHandler.handleMessage("Finding packer infrastructure VPC...")

        val packerVpcId = vpcService.findVpcByName(InfrastructureConfig.PACKER_VPC_NAME)

        if (packerVpcId == null) {
            outputHandler.handleMessage("No packer VPC found (${InfrastructureConfig.PACKER_VPC_NAME})")
            return TeardownResult.success(emptyList())
        }

        return teardownVpc(packerVpcId, dryRun)
    }

    /**
     * Executes the actual teardown of a VPC's resources in the correct order.
     *
     * @param resources The discovered resources to delete
     * @return TeardownResult with information about the operation
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeVpcTeardown(resources: DiscoveredResources): TeardownResult {
        val errors = mutableListOf<String>()

        try {
            outputHandler.handleMessage(
                "\nTearing down VPC: ${resources.vpcId}" +
                    (resources.vpcName?.let { " ($it)" } ?: ""),
            )

            // Execute teardown steps in order
            if (!terminateEmrClusters(resources, errors)) return TeardownResult.failure(errors, listOf(resources))
            if (!terminateEc2Instances(resources, errors)) return TeardownResult.failure(errors, listOf(resources))
            deleteNatGateways(resources, errors)
            deleteSecurityGroups(resources, errors)
            deleteRouteTables(resources, errors)
            deleteSubnets(resources, errors)
            deleteInternetGateway(resources, errors)
            deleteVpc(resources, errors)
        } catch (e: Exception) {
            errors.add(logError("Unexpected error during teardown", e))
        }

        return if (errors.isEmpty()) {
            TeardownResult.success(resources)
        } else {
            TeardownResult.failure(errors, listOf(resources))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun terminateEmrClusters(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ): Boolean {
        if (resources.emrClusterIds.isEmpty()) return true
        return try {
            emrTeardownService.terminateClusters(resources.emrClusterIds)
            emrTeardownService.waitForClustersTerminated(resources.emrClusterIds)
            true
        } catch (e: Exception) {
            errors.add(logError("Failed to terminate EMR clusters", e))
            true // EMR failure is not fatal, continue with teardown
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun terminateEc2Instances(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ): Boolean {
        if (resources.instanceIds.isEmpty()) return true
        return try {
            vpcService.terminateInstances(resources.instanceIds)
            vpcService.waitForInstancesTerminated(resources.instanceIds)
            true
        } catch (e: Exception) {
            errors.add(logError("Failed to terminate instances", e))
            false // Instance failure is fatal, cannot continue
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteNatGateways(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        if (resources.natGatewayIds.isEmpty()) return
        try {
            resources.natGatewayIds.forEach { vpcService.deleteNatGateway(it) }
            vpcService.waitForNatGatewaysDeleted(resources.natGatewayIds)
        } catch (e: Exception) {
            errors.add(logError("Failed to delete NAT gateways", e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteSecurityGroups(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        resources.securityGroupIds.forEach { sgId ->
            try {
                vpcService.deleteSecurityGroup(sgId)
            } catch (e: Exception) {
                errors.add(logError("Failed to delete security group $sgId", e))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteRouteTables(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        resources.routeTableIds.forEach { rtId ->
            try {
                vpcService.deleteRouteTable(rtId)
            } catch (e: Exception) {
                errors.add(logError("Failed to delete route table $rtId", e))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteSubnets(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        resources.subnetIds.forEach { subnetId ->
            try {
                vpcService.deleteSubnet(subnetId)
            } catch (e: Exception) {
                errors.add(logError("Failed to delete subnet $subnetId", e))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteInternetGateway(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        resources.internetGatewayId?.let { igwId ->
            try {
                vpcService.detachInternetGateway(igwId, resources.vpcId)
                vpcService.deleteInternetGateway(igwId)
            } catch (e: Exception) {
                errors.add(logError("Failed to delete internet gateway $igwId", e))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteVpc(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        try {
            vpcService.deleteVpc(resources.vpcId)
            outputHandler.handleMessage("VPC ${resources.vpcId} deleted successfully")
        } catch (e: Exception) {
            errors.add(logError("Failed to delete VPC ${resources.vpcId}", e))
        }
    }

    private fun logError(
        message: String,
        e: Exception,
    ): String {
        val error = "$message: ${e.message}"
        log.error(e) { error }
        return error
    }
}
