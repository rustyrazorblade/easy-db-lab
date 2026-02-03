package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for ensuring AWS VPC infrastructure exists.
 *
 * This service orchestrates the creation of VPC infrastructure for both
 * Packer AMI builds and cluster deployments. It uses the generic VpcService
 * to create and manage the required AWS resources.
 *
 * The infrastructure created includes:
 * - VPC with configurable CIDR
 * - One or more public subnets (optionally in specific availability zones)
 * - Internet gateway for external connectivity
 * - Security group with configurable ingress rules
 * - Route table with default route to internet gateway
 *
 * All resources are tagged with easy_cass_lab=1 to satisfy IAM policies.
 */
class AwsInfrastructureService(
    private val vpcService: VpcService,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

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
}
