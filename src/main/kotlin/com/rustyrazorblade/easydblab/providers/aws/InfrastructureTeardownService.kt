package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

/**
 * Service for orchestrating AWS infrastructure teardown.
 *
 * This service coordinates the deletion of VPC infrastructure and associated resources
 * in phases to ensure clean teardown without AWS dependency errors.
 *
 * ## Teardown Phases
 *
 * **Phase 1 (Parallel):** Long-running resource terminations run concurrently:
 * - EMR Clusters, OpenSearch domains, EC2 instances, NAT gateways
 *
 * **Phase 2 (ENI Wait):** Wait for lingering network interfaces to clear.
 * After Phase 1 resources are deleted, their ENIs may take time to detach.
 *
 * **Phase 3 (Sequential):** Fast VPC networking cleanup:
 * - Security groups (revoke rules, then delete)
 * - Route tables, subnets, internet gateway, VPC
 */
@Suppress("TooManyFunctions")
class InfrastructureTeardownService(
    private val vpcService: VpcService,
    private val emrService: EMRService,
    private val openSearchService: OpenSearchService,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}
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
        val emrClusterIds = emrService.findClustersInVpc(vpcId, subnetIds)
        val openSearchDomainNames = openSearchService.findDomainsInVpc(subnetIds)

        return DiscoveredResources(
            vpcId = vpcId,
            vpcName = vpcName,
            instanceIds = instanceIds,
            emrClusterIds = emrClusterIds,
            openSearchDomainNames = openSearchDomainNames,
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
        outputHandler.handleMessage("Finding all VPCs tagged with ${Constants.Vpc.TAG_KEY}=${Constants.Vpc.TAG_VALUE}...")

        val vpcIds = vpcService.findVpcsByTag(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)

        if (vpcIds.isEmpty()) {
            outputHandler.handleMessage("No VPCs found with tag ${Constants.Vpc.TAG_KEY}=${Constants.Vpc.TAG_VALUE}")
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
     * Executes the actual teardown of a VPC's resources in phases.
     *
     * Phase 1 (Parallel): EMR, OpenSearch, EC2, NAT gateways
     * Phase 2: Wait for ENIs to clear
     * Phase 3 (Sequential): Security groups, route tables, subnets, IGW, VPC
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

            // Phase 1: Parallel resource termination
            val phase1Result = executePhase1Parallel(resources)
            errors.addAll(phase1Result.errors)

            if (phase1Result.hasFatalFailure) {
                return TeardownResult.failure(errors, listOf(resources))
            }

            // Phase 2: Wait for lingering ENIs to clear
            waitForEniCleanup(resources, errors)

            // Phase 3: Sequential VPC networking cleanup
            revokeAllSecurityGroupRules(resources, errors)
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

    // ==================== Phase 1: Parallel Resource Termination ====================

    /**
     * Executes Phase 1 tasks in parallel using CompletableFuture.
     *
     * Each task collects errors into its own local list to avoid thread-safety issues.
     * After all tasks complete, errors are merged and fatal failures are checked.
     */
    private fun executePhase1Parallel(resources: DiscoveredResources): Phase1Result {
        val emrFuture =
            CompletableFuture.supplyAsync {
                terminateEmrClusters(resources)
            }
        val openSearchFuture =
            CompletableFuture.supplyAsync {
                deleteOpenSearchDomains(resources)
            }
        val ec2Future =
            CompletableFuture.supplyAsync {
                terminateEc2Instances(resources)
            }
        val natFuture =
            CompletableFuture.supplyAsync {
                deleteNatGateways(resources)
            }

        // Wait for all tasks to complete
        CompletableFuture.allOf(emrFuture, openSearchFuture, ec2Future, natFuture).join()

        val emrResult = emrFuture.get()
        val openSearchResult = openSearchFuture.get()
        val ec2Result = ec2Future.get()
        val natResult = natFuture.get()

        val allErrors = mutableListOf<String>()
        allErrors.addAll(emrResult.errors)
        allErrors.addAll(openSearchResult.errors)
        allErrors.addAll(ec2Result.errors)
        allErrors.addAll(natResult.errors)

        // Fatal if any critical task failed (EMR, OpenSearch, or EC2)
        val hasFatalFailure = !emrResult.success || !openSearchResult.success || !ec2Result.success

        return Phase1Result(errors = allErrors, hasFatalFailure = hasFatalFailure)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun terminateEmrClusters(resources: DiscoveredResources): TeardownStepResult {
        if (resources.emrClusterIds.isEmpty()) return TeardownStepResult.success()
        return try {
            emrService.terminateClusters(resources.emrClusterIds)
            emrService.waitForClustersTerminated(resources.emrClusterIds)
            TeardownStepResult.success()
        } catch (e: Exception) {
            TeardownStepResult.failure(logError("Failed to terminate EMR clusters", e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteOpenSearchDomains(resources: DiscoveredResources): TeardownStepResult {
        if (resources.openSearchDomainNames.isEmpty()) return TeardownStepResult.success()

        outputHandler.handleMessage("Deleting ${resources.openSearchDomainNames.size} OpenSearch domains...")

        val errors = mutableListOf<String>()
        val domainsToWait = mutableListOf<String>()

        resources.openSearchDomainNames.forEach { domainName ->
            try {
                openSearchService.deleteDomain(domainName)
                domainsToWait.add(domainName)
            } catch (e: Exception) {
                errors.add(logError("Failed to delete OpenSearch domain $domainName", e))
            }
        }

        // Wait for all domains to be fully deleted before proceeding
        // OpenSearch domains have ENIs in the VPC that block security group/subnet deletion
        var allDeleted = true
        domainsToWait.forEach { domainName ->
            try {
                openSearchService.waitForDomainDeleted(domainName)
            } catch (e: Exception) {
                errors.add(logError("Timeout waiting for OpenSearch domain $domainName to delete", e))
                outputHandler.handleMessage(
                    "Warning: OpenSearch domain $domainName is still deleting. " +
                        "VPC cleanup may fail - run teardown again later.",
                )
                allDeleted = false
            }
        }

        return TeardownStepResult(success = allDeleted, errors = errors)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun terminateEc2Instances(resources: DiscoveredResources): TeardownStepResult {
        if (resources.instanceIds.isEmpty()) return TeardownStepResult.success()
        return try {
            vpcService.terminateInstances(resources.instanceIds)
            vpcService.waitForInstancesTerminated(resources.instanceIds)
            TeardownStepResult.success()
        } catch (e: Exception) {
            TeardownStepResult.failure(logError("Failed to terminate instances", e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteNatGateways(resources: DiscoveredResources): TeardownStepResult {
        if (resources.natGatewayIds.isEmpty()) return TeardownStepResult.success()
        return try {
            resources.natGatewayIds.forEach { vpcService.deleteNatGateway(it) }
            vpcService.waitForNatGatewaysDeleted(resources.natGatewayIds)
            TeardownStepResult.success()
        } catch (e: Exception) {
            // NAT gateway failure is non-fatal
            TeardownStepResult(success = true, errors = listOf(logError("Failed to delete NAT gateways", e)))
        }
    }

    // ==================== Phase 2: ENI Wait ====================

    @Suppress("TooGenericExceptionCaught")
    private fun waitForEniCleanup(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        try {
            vpcService.waitForNetworkInterfacesCleared(resources.vpcId)
        } catch (e: Exception) {
            // ENI timeout is non-fatal — teardown continues, errors recorded
            errors.add(logError("Timeout waiting for network interfaces to clear in VPC ${resources.vpcId}", e))
            outputHandler.handleMessage(
                "Warning: Some network interfaces are still active. " +
                    "Subsequent deletions may fail with DependencyViolation errors.",
            )
        }
    }

    // ==================== Phase 3: Sequential VPC Cleanup ====================

    @Suppress("TooGenericExceptionCaught")
    private fun revokeAllSecurityGroupRules(
        resources: DiscoveredResources,
        errors: MutableList<String>,
    ) {
        if (resources.securityGroupIds.isEmpty()) return

        outputHandler.handleMessage("Revoking rules from ${resources.securityGroupIds.size} security groups...")

        resources.securityGroupIds.forEach { sgId ->
            try {
                vpcService.revokeSecurityGroupRules(sgId)
            } catch (e: Exception) {
                errors.add(logError("Failed to revoke rules from security group $sgId", e))
            }
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

/**
 * Result of a single teardown step, used for thread-safe error collection
 * in parallel Phase 1 tasks.
 */
data class TeardownStepResult(
    val success: Boolean,
    val errors: List<String> = emptyList(),
) {
    companion object {
        fun success(): TeardownStepResult = TeardownStepResult(success = true)

        fun failure(error: String): TeardownStepResult = TeardownStepResult(success = false, errors = listOf(error))
    }
}

/**
 * Aggregated result of all Phase 1 parallel tasks.
 */
data class Phase1Result(
    val errors: List<String>,
    val hasFatalFailure: Boolean,
)
