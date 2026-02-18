package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.InfrastructureConfig
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import com.rustyrazorblade.easydblab.providers.aws.VpcNetworkingConfig
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

/**
 * Service for managing AWS VPC infrastructure lifecycle.
 *
 * This service orchestrates both the creation and teardown of VPC infrastructure
 * for Packer AMI builds and cluster deployments. It uses the generic VpcService
 * to create and manage the required AWS resources.
 *
 * ## Infrastructure Creation
 *
 * The infrastructure created includes:
 * - VPC with configurable CIDR
 * - One or more public subnets (optionally in specific availability zones)
 * - Internet gateway for external connectivity
 * - Security group with configurable ingress rules
 * - Route table with default route to internet gateway
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
 *
 * All resources are tagged with easy_cass_lab=1 to satisfy IAM policies.
 */
@Suppress("TooManyFunctions")
class AwsInfrastructureService(
    private val vpcService: VpcService,
    private val emrService: EMRService,
    private val openSearchService: OpenSearchService,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    // ==================== Infrastructure Creation ====================

    /**
     * Ensures all required infrastructure exists and returns the details.
     *
     * This operation is idempotent - it will find existing resources or create
     * them if they don't exist. All resources are tagged according to the config.
     *
     * @param config Configuration describing the infrastructure to create
     * @return VpcInfrastructure containing the IDs of all created/found resources
     */
    fun ensureInfrastructure(config: InfrastructureConfig): VpcInfrastructure {
        log.info { "Ensuring infrastructure exists for: ${config.vpcName}" }
        outputHandler.handleMessage("Ensuring infrastructure exists for: ${config.vpcName}")

        // Create VPC
        val vpcId = vpcService.createVpc(config.vpcName, config.vpcCidr, config.tags)

        // Create or find internet gateway
        val igwId = vpcService.findOrCreateInternetGateway(vpcId, config.internetGatewayName, config.tags)

        // Create or find subnets
        val subnetIds =
            config.subnets.map { subnetConfig ->
                val subnetId =
                    vpcService.findOrCreateSubnet(
                        vpcId,
                        subnetConfig.name,
                        subnetConfig.cidr,
                        config.tags,
                        subnetConfig.availabilityZone,
                    )
                // Ensure routing is configured for each subnet
                vpcService.ensureRouteTable(vpcId, subnetId, igwId, config.tags)
                subnetId
            }

        // Create or find security group
        val sgId =
            vpcService.findOrCreateSecurityGroup(
                vpcId,
                config.securityGroupName,
                config.securityGroupDescription,
                config.tags,
            )

        // Configure all security group rules
        config.securityGroupRules.forEach { rule ->
            vpcService.authorizeSecurityGroupIngress(
                sgId,
                rule.fromPort,
                rule.toPort,
                rule.cidr,
                rule.protocol,
            )
        }

        val infrastructure = VpcInfrastructure(vpcId, subnetIds, sgId, igwId)

        log.info {
            "Infrastructure ready: VPC=$vpcId, Subnets=${subnetIds.size}, SG=$sgId, IGW=$igwId"
        }
        outputHandler.handleMessage("Infrastructure ready for: ${config.vpcName}")

        return infrastructure
    }

    /**
     * Ensures Packer AMI build infrastructure exists.
     *
     * This method reuses an existing Packer VPC if one exists, or creates
     * new infrastructure if not. This prevents duplicate VPCs from being
     * created on repeated Packer builds.
     *
     * @param sshPort SSH port to allow access on
     * @return VpcInfrastructure containing the IDs of all created/found resources
     */
    fun ensurePackerInfrastructure(sshPort: Int): VpcInfrastructure {
        val config = InfrastructureConfig.forPacker(sshPort)

        // Check for existing packer VPC first
        val existingVpcId = vpcService.findVpcByName(config.vpcName)
        if (existingVpcId != null) {
            log.info { "Reusing existing packer VPC: ${config.vpcName} ($existingVpcId)" }
            outputHandler.handleMessage("Using existing VPC: ${config.vpcName}")

            // Find or create remaining resources in existing VPC
            val igwId = vpcService.findOrCreateInternetGateway(existingVpcId, config.internetGatewayName, config.tags)
            val subnetConfig = config.subnets.first()
            val subnetId =
                vpcService.findOrCreateSubnet(
                    existingVpcId,
                    subnetConfig.name,
                    subnetConfig.cidr,
                    config.tags,
                )
            vpcService.ensureRouteTable(existingVpcId, subnetId, igwId, config.tags)
            val sgId =
                vpcService.findOrCreateSecurityGroup(
                    existingVpcId,
                    config.securityGroupName,
                    config.securityGroupDescription,
                    config.tags,
                )
            config.securityGroupRules.forEach { rule ->
                vpcService.authorizeSecurityGroupIngress(sgId, rule.fromPort, rule.toPort, rule.cidr, rule.protocol)
            }

            outputHandler.handleMessage("Infrastructure ready for: ${config.vpcName}")
            return VpcInfrastructure(existingVpcId, listOf(subnetId), sgId, igwId)
        }

        // No existing VPC, create everything
        return ensureInfrastructure(config)
    }

    /**
     * Ensures cluster infrastructure exists.
     *
     * This is a convenience method that creates infrastructure using the
     * cluster-specific configuration with multiple availability zones.
     *
     * @param clusterName Name of the cluster
     * @param availabilityZones List of availability zones to create subnets in
     * @param sshCidrs CIDRs allowed to SSH into the cluster
     * @param sshPort SSH port to allow access on
     * @return VpcInfrastructure containing the IDs of all created/found resources
     */
    fun ensureClusterInfrastructure(
        clusterName: String,
        availabilityZones: List<String>,
        sshCidrs: List<String>,
        sshPort: Int,
    ): VpcInfrastructure {
        val config = InfrastructureConfig.forCluster(clusterName, availabilityZones, sshCidrs, sshPort)
        return ensureInfrastructure(config)
    }

    /**
     * Sets up networking within an existing VPC for cluster deployment.
     *
     * This method is used when the VPC already exists and we need to create:
     * - Subnets in each availability zone
     * - Internet gateway for external connectivity
     * - Route tables for each subnet
     * - Security group with SSH access and VPC internal traffic rules
     *
     * @param config VPC networking configuration
     * @param externalIpProvider Function to get external IP (used when isOpen is false)
     * @return VpcInfrastructure with subnet IDs, security group ID, and IGW ID
     */
    fun setupVpcNetworking(
        config: VpcNetworkingConfig,
        externalIpProvider: () -> String,
    ): VpcInfrastructure {
        log.info { "Setting up VPC networking for cluster: ${config.clusterName}" }
        outputHandler.handleMessage("Setting up VPC networking for: ${config.clusterName}")

        val baseTags = config.tags + mapOf("easy_cass_lab" to "1", "ClusterId" to config.clusterId)
        val cidrBlock = CidrBlock(config.vpcCidr)

        // Construct full availability zone names
        val fullAzNames = config.availabilityZones.map { config.region + it }

        // Create subnets in each AZ using the configured CIDR
        val subnetIds =
            fullAzNames.mapIndexed { index, az ->
                vpcService.findOrCreateSubnet(
                    vpcId = config.vpcId,
                    name = "${config.clusterName}-subnet-$index",
                    cidr = cidrBlock.subnetCidr(index),
                    tags = baseTags,
                    availabilityZone = az,
                )
            }

        // Create internet gateway
        val igwId =
            vpcService.findOrCreateInternetGateway(
                vpcId = config.vpcId,
                name = "${config.clusterName}-igw",
                tags = baseTags,
            )

        // Ensure route tables are configured for each subnet
        subnetIds.forEach { subnetId ->
            vpcService.ensureRouteTable(config.vpcId, subnetId, igwId, baseTags)
        }

        // Create security group
        val securityGroupId =
            vpcService.findOrCreateSecurityGroup(
                vpcId = config.vpcId,
                name = "${config.clusterName}-sg",
                description = "Security group for easy-db-lab cluster ${config.clusterName}",
                tags = baseTags,
            )

        // Configure SSH access - either from anywhere or from the user's external IP
        val sshCidr = if (config.isOpen) "0.0.0.0/0" else "${externalIpProvider()}/32"
        vpcService.authorizeSecurityGroupIngress(securityGroupId, Constants.Network.SSH_PORT, Constants.Network.SSH_PORT, sshCidr)

        // Allow all traffic within the VPC (for Cassandra communication) - both TCP and UDP
        vpcService.authorizeSecurityGroupIngress(
            securityGroupId,
            Constants.Network.MIN_PORT,
            Constants.Network.MAX_PORT,
            config.vpcCidr,
            "tcp",
        )
        vpcService.authorizeSecurityGroupIngress(
            securityGroupId,
            Constants.Network.MIN_PORT,
            Constants.Network.MAX_PORT,
            config.vpcCidr,
            "udp",
        )

        val infrastructure = VpcInfrastructure(config.vpcId, subnetIds, securityGroupId, igwId)

        log.info {
            "VPC networking ready: Subnets=${subnetIds.size}, SG=$securityGroupId, IGW=$igwId"
        }
        outputHandler.handleMessage("VPC networking ready for: ${config.clusterName}")

        return infrastructure
    }

    // ==================== Infrastructure Teardown ====================

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

    // ==================== Private Teardown Implementation ====================

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
