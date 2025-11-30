package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.output.OutputHandler
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

        // Create or find VPC
        val vpcId = vpcService.findOrCreateVpc(config.vpcName, config.vpcCidr, config.tags)

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
                vpcService.ensureRouteTable(vpcId, subnetId, igwId)
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
     * This is a convenience method that creates infrastructure using the
     * packer-specific configuration.
     *
     * @param sshPort SSH port to allow access on
     * @return VpcInfrastructure containing the IDs of all created/found resources
     */
    fun ensurePackerInfrastructure(sshPort: Int): VpcInfrastructure {
        val config = InfrastructureConfig.forPacker(sshPort)
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
}
