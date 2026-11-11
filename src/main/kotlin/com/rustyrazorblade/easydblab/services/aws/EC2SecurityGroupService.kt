package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.Cidr
import com.rustyrazorblade.easydblab.providers.aws.IngressAuthorization
import com.rustyrazorblade.easydblab.providers.aws.IngressRevocation
import com.rustyrazorblade.easydblab.providers.aws.ResourceDescription
import com.rustyrazorblade.easydblab.providers.aws.ResourceName
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupId
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupNotFoundException
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupOperations
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import com.rustyrazorblade.easydblab.providers.aws.WellKnownPorts
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import com.rustyrazorblade.easydblab.providers.aws.withVpcTeardownRetry
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.IpPermission
import software.amazon.awssdk.services.ec2.model.IpRange
import software.amazon.awssdk.services.ec2.model.RevokeSecurityGroupEgressRequest
import software.amazon.awssdk.services.ec2.model.RevokeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.SecurityGroup

/**
 * Security group management with the AWS EC2 SDK: find or create, ingress rules, describe,
 * revoke and delete.
 *
 * It holds the [SecurityGroupOperations] half of [com.rustyrazorblade.easydblab.providers.aws.VpcService],
 * split out of [EC2VpcService] so each class has one resource to manage.  [EC2VpcService]
 * delegates to it, so callers still inject one `VpcService`.
 */
class EC2SecurityGroupService(
    private val ec2Client: Ec2Client,
    private val eventBus: EventBus,
) : SecurityGroupOperations {
    companion object {
        private val log = KotlinLogging.logger {}

        private const val GROUP_NOT_FOUND = "InvalidGroup.NotFound"
        private const val PERMISSION_DUPLICATE = "InvalidPermission.Duplicate"
        private const val PERMISSION_NOT_FOUND = "InvalidPermission.NotFound"

        private val PORT_DESCRIPTIONS =
            mapOf(
                WellKnownPorts.SSH to "SSH",
                WellKnownPorts.HTTP to "HTTP",
                WellKnownPorts.HTTPS to "HTTPS",
                WellKnownPorts.MYSQL to "MySQL",
                WellKnownPorts.POSTGRESQL to "PostgreSQL",
                WellKnownPorts.REDIS to "Redis",
                WellKnownPorts.CASSANDRA_INTERNODE to "Cassandra Inter-node",
                WellKnownPorts.CASSANDRA_INTERNODE_SSL to "Cassandra Inter-node SSL",
                WellKnownPorts.CASSANDRA_CQL to "Cassandra CQL",
                WellKnownPorts.CASSANDRA_CQL_SSL to "Cassandra CQL SSL",
                WellKnownPorts.CASSANDRA_THRIFT to "Cassandra Thrift",
                WellKnownPorts.CASSANDRA_JMX to "Cassandra JMX",
            )
    }

    override fun findOrCreateSecurityGroup(
        vpcId: VpcId,
        name: ResourceName,
        description: ResourceDescription,
        tags: Map<String, String>,
    ): SecurityGroupId {
        // Try to find existing security group by name and VPC ID
        val existingSgId = findSecurityGroupByNameAndVpc(name, vpcId)
        if (existingSgId != null) {
            log.info { "Found existing security group: $name ($existingSgId)" }
            eventBus.emit(Event.Infra.SecurityGroupUsing(name))
            return existingSgId
        }

        // Create new security group
        log.info { "Creating security group: $name in VPC: $vpcId" }
        eventBus.emit(Event.Infra.SecurityGroupCreating(name))

        val allTags = tags + ("Name" to name)
        val tagSpecification = buildTagSpecification("security-group", allTags)

        val createRequest =
            CreateSecurityGroupRequest
                .builder()
                .groupName(name)
                .description(description)
                .vpcId(vpcId)
                .tagSpecifications(tagSpecification)
                .build()

        val createResponse = withAwsRetry("create-sg") { ec2Client.createSecurityGroup(createRequest) }
        val sgId = createResponse.groupId()

        log.info { "Created security group: $name ($sgId)" }
        return sgId
    }

    override fun authorizeSecurityGroupIngress(
        securityGroupId: SecurityGroupId,
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String,
    ): IngressAuthorization {
        val portDesc = describeIngressPorts(fromPort, toPort, protocol)
        if (describeExistingGroup(securityGroupId).hasIngressRule(fromPort, toPort, cidr, protocol)) {
            log.info { "Ingress rule already exists for $portDesc from $cidr ($protocol)" }
            return IngressAuthorization.ALREADY_PRESENT
        }

        val authorizeRequest =
            AuthorizeSecurityGroupIngressRequest
                .builder()
                .groupId(securityGroupId)
                .ipPermissions(ingressPermission(fromPort, toPort, cidr, protocol))
                .build()

        return try {
            requireGroup(securityGroupId) { ec2Client.authorizeSecurityGroupIngress(authorizeRequest) }
            log.info { "Added ingress rule for $portDesc from $cidr ($protocol)" }
            IngressAuthorization.ADDED
        } catch (e: Ec2Exception) {
            if (e.awsErrorDetails()?.errorCode() != PERMISSION_DUPLICATE) throw e
            log.info { "Ingress rule already exists, continuing" }
            IngressAuthorization.ALREADY_PRESENT
        }
    }

    /**
     * Describes the group, then revokes only the exact permission asked for.  Describing first
     * keeps the answer the same against EC2 and LocalStack, which report a revoke of a missing
     * rule differently.
     */
    override fun revokeSecurityGroupIngress(
        securityGroupId: SecurityGroupId,
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String,
    ): IngressRevocation {
        val portDesc = describeIngressPorts(fromPort, toPort, protocol)
        if (!describeExistingGroup(securityGroupId).hasIngressRule(fromPort, toPort, cidr, protocol)) {
            log.info { "No ingress rule for $portDesc from $cidr ($protocol) to revoke" }
            return IngressRevocation.NOT_FOUND
        }

        val revokeRequest =
            RevokeSecurityGroupIngressRequest
                .builder()
                .groupId(securityGroupId)
                .ipPermissions(ingressPermission(fromPort, toPort, cidr, protocol))
                .build()

        return try {
            requireGroup(securityGroupId) { ec2Client.revokeSecurityGroupIngress(revokeRequest) }
            log.info { "Revoked ingress rule for $portDesc from $cidr ($protocol)" }
            IngressRevocation.REMOVED
        } catch (e: Ec2Exception) {
            if (e.awsErrorDetails()?.errorCode() != PERMISSION_NOT_FOUND) throw e
            log.info { "Ingress rule for $portDesc from $cidr ($protocol) was already gone" }
            IngressRevocation.NOT_FOUND
        }
    }

    /**
     * Revokes all ingress and egress rules from a security group.
     * This is necessary to break circular dependencies between security groups
     * (e.g., EMR master/slave security groups reference each other).
     */
    override fun revokeSecurityGroupRules(securityGroupId: SecurityGroupId) {
        log.info { "Revoking all rules from security group: $securityGroupId" }

        val sg = describeGroup(securityGroupId)

        // Revoke all ingress rules
        if (sg.ipPermissions().isNotEmpty()) {
            val revokeIngressRequest =
                RevokeSecurityGroupIngressRequest
                    .builder()
                    .groupId(securityGroupId)
                    .ipPermissions(sg.ipPermissions())
                    .build()

            try {
                ec2Client.revokeSecurityGroupIngress(revokeIngressRequest)
                log.info { "Revoked ${sg.ipPermissions().size} ingress rules from $securityGroupId" }
            } catch (e: Ec2Exception) {
                log.warn { "Failed to revoke ingress rules from $securityGroupId: ${e.message}" }
            }
        }

        // Revoke all egress rules
        if (sg.ipPermissionsEgress().isNotEmpty()) {
            val revokeEgressRequest =
                RevokeSecurityGroupEgressRequest
                    .builder()
                    .groupId(securityGroupId)
                    .ipPermissions(sg.ipPermissionsEgress())
                    .build()

            try {
                ec2Client.revokeSecurityGroupEgress(revokeEgressRequest)
                log.info { "Revoked ${sg.ipPermissionsEgress().size} egress rules from $securityGroupId" }
            } catch (e: Ec2Exception) {
                log.warn { "Failed to revoke egress rules from $securityGroupId: ${e.message}" }
            }
        }
    }

    override fun deleteSecurityGroup(securityGroupId: SecurityGroupId) {
        log.info { "Deleting security group: $securityGroupId" }
        eventBus.emit(Event.Infra.SecurityGroupDeleting(securityGroupId))

        val deleteRequest =
            DeleteSecurityGroupRequest
                .builder()
                .groupId(securityGroupId)
                .build()

        withVpcTeardownRetry("delete-security-group") { ec2Client.deleteSecurityGroup(deleteRequest) }
        log.info { "Deleted security group: $securityGroupId" }
    }

    override fun describeSecurityGroup(securityGroupId: String): SecurityGroupDetails {
        log.debug { "Describing security group: $securityGroupId" }

        val sg = describeGroup(securityGroupId)

        val inboundRules =
            sg.ipPermissions().map { permission ->
                SecurityGroupRuleInfo(
                    protocol = formatProtocol(permission.ipProtocol()),
                    fromPort = permission.fromPort(),
                    toPort = permission.toPort(),
                    cidrBlocks = permission.ipRanges().mapNotNull { it.cidrIp() },
                    description =
                        permission.ipRanges().firstOrNull()?.description()
                            ?: getPortDescription(permission.fromPort(), permission.toPort()),
                )
            }

        val outboundRules =
            sg.ipPermissionsEgress().map { permission ->
                SecurityGroupRuleInfo(
                    protocol = formatProtocol(permission.ipProtocol()),
                    fromPort = permission.fromPort(),
                    toPort = permission.toPort(),
                    cidrBlocks = permission.ipRanges().mapNotNull { it.cidrIp() },
                    description =
                        permission.ipRanges().firstOrNull()?.description()
                            ?: getPortDescription(permission.fromPort(), permission.toPort()),
                )
            }

        return SecurityGroupDetails(
            securityGroupId = sg.groupId(),
            name = sg.groupName(),
            description = sg.description(),
            vpcId = sg.vpcId(),
            inboundRules = inboundRules,
            outboundRules = outboundRules,
        )
    }

    private fun findSecurityGroupByNameAndVpc(
        name: ResourceName,
        vpcId: VpcId,
    ): SecurityGroupId? {
        val describeRequest =
            DescribeSecurityGroupsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("group-name")
                        .values(name)
                        .build(),
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val sgs = withAwsRetry("find-security-group") { ec2Client.describeSecurityGroups(describeRequest) }.securityGroups()
        return sgs.firstOrNull()?.groupId()
    }

    /**
     * Describes one security group by ID.  EC2 answers `InvalidGroup.NotFound` for an ID it does
     * not know, so an empty answer is not a real case; it still fails with a message naming the ID.
     */
    private fun describeGroup(securityGroupId: SecurityGroupId): SecurityGroup {
        val request =
            DescribeSecurityGroupsRequest
                .builder()
                .groupIds(securityGroupId)
                .build()

        return withAwsRetry("describe-security-group") { ec2Client.describeSecurityGroups(request) }
            .securityGroups()
            .firstOrNull()
            ?: error("Security group $securityGroupId not found")
    }

    /** [describeGroup] for the rule changes, where a missing group is [SecurityGroupNotFoundException]. */
    private fun describeExistingGroup(securityGroupId: SecurityGroupId): SecurityGroup =
        requireGroup(securityGroupId) { describeGroup(securityGroupId) }

    /** Runs [call], turning EC2's `InvalidGroup.NotFound` into [SecurityGroupNotFoundException]. */
    private fun <T> requireGroup(
        securityGroupId: SecurityGroupId,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: Ec2Exception) {
            if (e.awsErrorDetails()?.errorCode() != GROUP_NOT_FOUND) throw e
            throw SecurityGroupNotFoundException(securityGroupId, e)
        }

    private fun SecurityGroup.hasIngressRule(
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String,
    ): Boolean =
        ipPermissions().any { permission ->
            permission.fromPort() == fromPort &&
                permission.toPort() == toPort &&
                permission.ipProtocol() == protocol &&
                permission.ipRanges().any { it.cidrIp() == cidr }
        }

    private fun ingressPermission(
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String,
    ): IpPermission =
        IpPermission
            .builder()
            .ipProtocol(protocol)
            .fromPort(fromPort)
            .toPort(toPort)
            .ipRanges(
                IpRange
                    .builder()
                    .cidrIp(cidr)
                    .build(),
            ).build()

    private fun formatProtocol(protocol: String): String =
        when (protocol) {
            "-1" -> "All"
            else -> protocol.uppercase()
        }

    private fun getPortDescription(
        fromPort: Int?,
        toPort: Int?,
    ): String? {
        if (fromPort == null || toPort == null) return "All traffic"
        if (fromPort != toPort) return null
        return PORT_DESCRIPTIONS[fromPort]
    }
}

/**
 * Human-readable description of the ports an ingress rule opens. ICMP has no ports;
 * EC2 encodes "all types" as -1/-1, which would otherwise render as "port -1".
 */
internal fun describeIngressPorts(
    fromPort: Int,
    toPort: Int,
    protocol: String,
): String =
    when {
        protocol == Constants.Network.ICMP_PROTOCOL && fromPort == Constants.Network.ALL_ICMP_TYPES ->
            "all ICMP types"
        fromPort == toPort -> "port $fromPort"
        else -> "ports $fromPort-$toPort"
    }
