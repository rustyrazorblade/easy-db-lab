package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.CreateInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.CreateRouteRequest
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest
import software.amazon.awssdk.services.ec2.model.DeleteInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.DeleteNatGatewayRequest
import software.amazon.awssdk.services.ec2.model.DeleteRouteTableRequest
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInternetGatewaysRequest
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysRequest
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.DetachInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.DisassociateRouteTableRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.IpPermission
import software.amazon.awssdk.services.ec2.model.IpRange
import software.amazon.awssdk.services.ec2.model.NatGatewayState
import software.amazon.awssdk.services.ec2.model.RevokeSecurityGroupEgressRequest
import software.amazon.awssdk.services.ec2.model.RevokeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest

/**
 * Implementation of VpcService using AWS EC2 SDK.
 *
 * This service manages AWS VPC infrastructure with idempotent operations.
 * All resources are tagged with a Name tag and additional custom tags.
 * Operations use find-or-create pattern to ensure idempotency.
 */
@Suppress("TooManyFunctions", "LargeClass")
class EC2VpcService(
    private val ec2Client: Ec2Client,
    private val outputHandler: OutputHandler,
) : VpcService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun createVpc(
        name: ResourceName,
        cidr: Cidr,
        tags: Map<String, String>,
    ): VpcId {
        log.info { "Creating VPC: $name with CIDR: $cidr" }
        outputHandler.handleMessage("Creating VPC: $name")

        val allTags = tags + ("Name" to name)
        log.info { "Creating VPC with tags $allTags" }
        val tagSpecification = buildTagSpecification("vpc", allTags)

        val createRequest =
            CreateVpcRequest
                .builder()
                .cidrBlock(cidr)
                .tagSpecifications(tagSpecification)
                .build()

        val createResponse = RetryUtil.withAwsRetry("create-vpc") { ec2Client.createVpc(createRequest) }
        val vpcId = createResponse.vpc().vpcId()

        log.info { "Created VPC: $name ($vpcId)" }
        return vpcId
    }

    override fun findOrCreateSubnet(
        vpcId: VpcId,
        name: ResourceName,
        cidr: Cidr,
        tags: Map<String, String>,
        availabilityZone: String?,
    ): SubnetId {
        // Try to find existing subnet by Name tag and VPC ID
        val existingSubnetId = findSubnetByNameAndVpc(name, vpcId)
        if (existingSubnetId != null) {
            log.info { "Found existing subnet: $name ($existingSubnetId)" }
            outputHandler.handleMessage("Using existing subnet: $name")
            // Ensure auto-assign public IP is enabled (idempotent operation)
            enableAutoAssignPublicIp(existingSubnetId)
            return existingSubnetId
        }

        // Create new subnet
        val azInfo = availabilityZone?.let { " in AZ: $it" } ?: ""
        log.info { "Creating subnet: $name in VPC: $vpcId with CIDR: $cidr$azInfo" }
        outputHandler.handleMessage("Creating subnet: $name")

        val allTags = tags + ("Name" to name)
        log.debug { "Subnet tags being applied: $allTags" }
        val tagSpecification = buildTagSpecification("subnet", allTags)

        val createRequestBuilder =
            CreateSubnetRequest
                .builder()
                .vpcId(vpcId)
                .cidrBlock(cidr)
                .tagSpecifications(tagSpecification)

        // Add availability zone if specified
        if (availabilityZone != null) {
            createRequestBuilder.availabilityZone(availabilityZone)
        }

        val createRequest = createRequestBuilder.build()

        log.debug { "CreateSubnet request: vpcId=$vpcId, cidr=$cidr, az=$availabilityZone, tags=${tagSpecification.tags()}" }
        val createResponse = RetryUtil.withAwsRetry("create-subnet") { ec2Client.createSubnet(createRequest) }
        val subnetId = createResponse.subnet().subnetId()

        // Enable auto-assign public IP for instances launched in this subnet
        enableAutoAssignPublicIp(subnetId)

        log.info { "Created subnet: $name ($subnetId)" }
        return subnetId
    }

    override fun findOrCreateInternetGateway(
        vpcId: VpcId,
        name: ResourceName,
        tags: Map<String, String>,
    ): InternetGatewayId {
        // Try to find existing internet gateway by Name tag
        val existingIgwId = findInternetGatewayByNameAndVpc(name, vpcId)
        if (existingIgwId != null) {
            log.info { "Found existing internet gateway: $name ($existingIgwId)" }
            outputHandler.handleMessage("Using existing internet gateway: $name")
            return existingIgwId
        }

        // Create new internet gateway
        log.info { "Creating internet gateway: $name" }
        outputHandler.handleMessage("Creating internet gateway: $name")

        val allTags = tags + ("Name" to name)
        val tagSpecification = buildTagSpecification("internet-gateway", allTags)

        val createRequest =
            CreateInternetGatewayRequest
                .builder()
                .tagSpecifications(tagSpecification)
                .build()

        val createResponse = RetryUtil.withAwsRetry("create-igw") { ec2Client.createInternetGateway(createRequest) }
        val igwId = createResponse.internetGateway().internetGatewayId()

        // Attach to VPC
        val attachRequest =
            AttachInternetGatewayRequest
                .builder()
                .internetGatewayId(igwId)
                .vpcId(vpcId)
                .build()

        RetryUtil.withAwsRetry("attach-igw") { ec2Client.attachInternetGateway(attachRequest) }
        log.info { "Created and attached internet gateway: $name ($igwId) to VPC: $vpcId" }

        return igwId
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
            outputHandler.handleMessage("Using existing security group: $name")
            return existingSgId
        }

        // Create new security group
        log.info { "Creating security group: $name in VPC: $vpcId" }
        outputHandler.handleMessage("Creating security group: $name")

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

        val createResponse = RetryUtil.withAwsRetry("create-sg") { ec2Client.createSecurityGroup(createRequest) }
        val sgId = createResponse.groupId()

        log.info { "Created security group: $name ($sgId)" }
        return sgId
    }

    override fun ensureRouteTable(
        vpcId: VpcId,
        subnetId: SubnetId,
        igwId: InternetGatewayId,
        tags: Map<String, String>,
    ) {
        // Get the main route table for the VPC
        val describeRequest =
            DescribeRouteTablesRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                    Filter
                        .builder()
                        .name("association.main")
                        .values("true")
                        .build(),
                ).build()

        val routeTables = ec2Client.describeRouteTables(describeRequest).routeTables()
        if (routeTables.isEmpty()) {
            log.error { "No main route table found for VPC: $vpcId" }
            error("No main route table found for VPC: $vpcId")
        }

        val routeTableId = routeTables[0].routeTableId()

        // Apply tags to the route table if provided
        if (tags.isNotEmpty()) {
            createTags(routeTableId, tags)
            log.info { "Applied tags to route table: $routeTableId" }
        }

        // Check if default route already exists
        val existingRoute =
            routeTables[0].routes().any { route ->
                route.destinationCidrBlock() == "0.0.0.0/0" && route.gatewayId() == igwId
            }

        if (existingRoute) {
            log.info { "Default route already exists in route table: $routeTableId" }
            return
        }

        // Create default route to internet gateway
        try {
            val createRouteRequest =
                CreateRouteRequest
                    .builder()
                    .routeTableId(routeTableId)
                    .destinationCidrBlock("0.0.0.0/0")
                    .gatewayId(igwId)
                    .build()

            ec2Client.createRoute(createRouteRequest)
            log.info { "Created default route to internet gateway in route table: $routeTableId" }
            outputHandler.handleMessage("Configured routing to internet gateway")
        } catch (e: Ec2Exception) {
            if (e.awsErrorDetails()?.errorCode() == "RouteAlreadyExists") {
                log.info { "Route already exists, continuing" }
            } else {
                throw e
            }
        }
    }

    override fun authorizeSecurityGroupIngress(
        securityGroupId: SecurityGroupId,
        fromPort: Int,
        toPort: Int,
        cidr: Cidr,
        protocol: String,
    ) {
        // Check if rule already exists
        val describeRequest =
            DescribeSecurityGroupsRequest
                .builder()
                .groupIds(securityGroupId)
                .build()

        val securityGroups = ec2Client.describeSecurityGroups(describeRequest).securityGroups()
        if (securityGroups.isEmpty()) {
            log.error { "Security group not found: $securityGroupId" }
            throw IllegalArgumentException("Security group not found: $securityGroupId")
        }

        val existingRule =
            securityGroups[0].ipPermissions().any { permission ->
                permission.fromPort() == fromPort &&
                    permission.toPort() == toPort &&
                    permission.ipProtocol() == protocol &&
                    permission.ipRanges().any { it.cidrIp() == cidr }
            }

        if (existingRule) {
            val portDesc = if (fromPort == toPort) "port $fromPort" else "ports $fromPort-$toPort"
            log.info { "Ingress rule already exists for $portDesc from $cidr ($protocol)" }
            return
        }

        // Add ingress rule
        try {
            val ipPermission =
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

            val authorizeRequest =
                AuthorizeSecurityGroupIngressRequest
                    .builder()
                    .groupId(securityGroupId)
                    .ipPermissions(ipPermission)
                    .build()

            ec2Client.authorizeSecurityGroupIngress(authorizeRequest)
            val portDesc = if (fromPort == toPort) "port $fromPort" else "ports $fromPort-$toPort"
            log.info { "Added ingress rule for $portDesc from $cidr ($protocol)" }
            outputHandler.handleMessage("Configured security group ingress rule for $portDesc")
        } catch (e: Ec2Exception) {
            if (e.awsErrorDetails()?.errorCode() == "InvalidPermission.Duplicate") {
                log.info { "Ingress rule already exists, continuing" }
            } else {
                throw e
            }
        }
    }

    private fun findSubnetByNameAndVpc(
        name: ResourceName,
        vpcId: VpcId,
    ): SubnetId? {
        val describeRequest =
            DescribeSubnetsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("tag:Name")
                        .values(name)
                        .build(),
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val subnets = ec2Client.describeSubnets(describeRequest).subnets()
        return subnets.firstOrNull()?.subnetId()
    }

    private fun findInternetGatewayByNameAndVpc(
        name: ResourceName,
        vpcId: VpcId,
    ): InternetGatewayId? {
        val describeRequest =
            DescribeInternetGatewaysRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("tag:Name")
                        .values(name)
                        .build(),
                    Filter
                        .builder()
                        .name("attachment.vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val igws = ec2Client.describeInternetGateways(describeRequest).internetGateways()
        return igws.firstOrNull()?.internetGatewayId()
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

        val sgs = ec2Client.describeSecurityGroups(describeRequest).securityGroups()
        return sgs.firstOrNull()?.groupId()
    }

    private fun createTags(
        resourceId: String,
        tags: Map<String, String>,
    ) {
        val awsTags =
            tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        val createTagsRequest =
            CreateTagsRequest
                .builder()
                .resources(resourceId)
                .tags(awsTags)
                .build()

        ec2Client.createTags(createTagsRequest)
    }

    private fun buildTagSpecification(
        resourceType: String,
        tags: Map<String, String>,
    ): software.amazon.awssdk.services.ec2.model.TagSpecification {
        val awsTags =
            tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        return software.amazon.awssdk.services.ec2.model.TagSpecification
            .builder()
            .resourceType(resourceType)
            .tags(awsTags)
            .build()
    }

    private fun enableAutoAssignPublicIp(subnetId: SubnetId) {
        log.info { "Enabling auto-assign public IP for subnet: $subnetId" }

        val modifyRequest =
            software.amazon.awssdk.services.ec2.model.ModifySubnetAttributeRequest
                .builder()
                .subnetId(subnetId)
                .mapPublicIpOnLaunch(
                    software.amazon.awssdk.services.ec2.model.AttributeBooleanValue
                        .builder()
                        .value(true)
                        .build(),
                ).build()

        ec2Client.modifySubnetAttribute(modifyRequest)
        log.info { "Auto-assign public IP enabled for subnet: $subnetId" }
    }

    // ==================== Discovery Methods Implementation ====================

    override fun findVpcsByTag(
        tagKey: String,
        tagValue: String,
    ): List<VpcId> {
        log.info { "Finding VPCs with tag $tagKey=$tagValue" }

        val describeRequest =
            DescribeVpcsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("tag:$tagKey")
                        .values(tagValue)
                        .build(),
                ).build()

        val vpcs = RetryUtil.withAwsRetry("find-vpcs-by-tag") { ec2Client.describeVpcs(describeRequest).vpcs() }
        val vpcIds = vpcs.map { it.vpcId() }

        log.info { "Found ${vpcIds.size} VPCs with tag $tagKey=$tagValue" }
        return vpcIds
    }

    override fun findVpcByName(name: ResourceName): VpcId? {
        log.info { "Finding VPC by name: $name" }

        val describeRequest =
            DescribeVpcsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("tag:Name")
                        .values(name)
                        .build(),
                ).build()

        val vpcs = RetryUtil.withAwsRetry("find-vpc-by-name") { ec2Client.describeVpcs(describeRequest).vpcs() }
        return vpcs.firstOrNull()?.vpcId()
    }

    override fun getVpcName(vpcId: VpcId): String? {
        log.debug { "Getting name for VPC: $vpcId" }

        val describeRequest =
            DescribeVpcsRequest
                .builder()
                .vpcIds(vpcId)
                .build()

        val vpcs = RetryUtil.withAwsRetry("get-vpc-name") { ec2Client.describeVpcs(describeRequest).vpcs() }
        val vpc = vpcs.firstOrNull() ?: return null

        return vpc.tags().firstOrNull { it.key() == "Name" }?.value()
    }

    override fun getVpcTags(vpcId: VpcId): Map<String, String> {
        log.debug { "Getting tags for VPC: $vpcId" }

        val describeRequest =
            DescribeVpcsRequest
                .builder()
                .vpcIds(vpcId)
                .build()

        val vpcs = RetryUtil.withAwsRetry("get-vpc-tags") { ec2Client.describeVpcs(describeRequest).vpcs() }
        val vpc =
            vpcs.firstOrNull()
                ?: error("VPC $vpcId not found")

        return vpc.tags().associate { it.key() to it.value() }
    }

    override fun addTagsToVpc(
        vpcId: VpcId,
        tags: Map<String, String>,
    ) {
        log.info { "Adding tags to VPC $vpcId: ${tags.keys}" }
        createTags(vpcId, tags)
    }

    override fun findInstancesInVpc(vpcId: VpcId): List<InstanceId> {
        log.info { "Finding EC2 instances in VPC: $vpcId" }

        val describeRequest =
            DescribeInstancesRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                    Filter
                        .builder()
                        .name("instance-state-name")
                        .values(
                            InstanceStateName.PENDING.toString(),
                            InstanceStateName.RUNNING.toString(),
                            InstanceStateName.STOPPING.toString(),
                            InstanceStateName.STOPPED.toString(),
                        ).build(),
                ).build()

        val reservations = RetryUtil.withAwsRetry("find-instances") { ec2Client.describeInstances(describeRequest).reservations() }
        val instanceIds = reservations.flatMap { it.instances() }.map { it.instanceId() }

        log.info { "Found ${instanceIds.size} instances in VPC: $vpcId" }
        return instanceIds
    }

    override fun findSubnetsInVpc(vpcId: VpcId): List<SubnetId> {
        log.info { "Finding subnets in VPC: $vpcId" }

        val describeRequest =
            DescribeSubnetsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val subnets = RetryUtil.withAwsRetry("find-subnets") { ec2Client.describeSubnets(describeRequest).subnets() }
        val subnetIds = subnets.map { it.subnetId() }

        log.info { "Found ${subnetIds.size} subnets in VPC: $vpcId" }
        return subnetIds
    }

    override fun findSecurityGroupsInVpc(vpcId: VpcId): List<SecurityGroupId> {
        log.info { "Finding security groups in VPC: $vpcId" }

        val describeRequest =
            DescribeSecurityGroupsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val securityGroups =
            RetryUtil.withAwsRetry("find-security-groups") {
                ec2Client.describeSecurityGroups(describeRequest).securityGroups()
            }

        // Exclude default security group as it cannot be deleted
        val nonDefaultSgs = securityGroups.filter { it.groupName() != "default" }
        val sgIds = nonDefaultSgs.map { it.groupId() }

        log.info { "Found ${sgIds.size} non-default security groups in VPC: $vpcId" }
        return sgIds
    }

    override fun findNatGatewaysInVpc(vpcId: VpcId): List<NatGatewayId> {
        log.info { "Finding NAT gateways in VPC: $vpcId" }

        val describeRequest =
            DescribeNatGatewaysRequest
                .builder()
                .filter(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                    Filter
                        .builder()
                        .name("state")
                        .values(
                            NatGatewayState.AVAILABLE.toString(),
                            NatGatewayState.PENDING.toString(),
                        ).build(),
                ).build()

        val natGateways =
            RetryUtil.withAwsRetry("find-nat-gateways") {
                ec2Client.describeNatGateways(describeRequest).natGateways()
            }
        val natGatewayIds = natGateways.map { it.natGatewayId() }

        log.info { "Found ${natGatewayIds.size} NAT gateways in VPC: $vpcId" }
        return natGatewayIds
    }

    override fun findInternetGatewayByVpc(vpcId: VpcId): InternetGatewayId? {
        log.info { "Finding internet gateway for VPC: $vpcId" }

        val describeRequest =
            DescribeInternetGatewaysRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("attachment.vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val igws = RetryUtil.withAwsRetry("find-igw") { ec2Client.describeInternetGateways(describeRequest).internetGateways() }
        return igws.firstOrNull()?.internetGatewayId()
    }

    override fun findRouteTablesInVpc(vpcId: VpcId): List<RouteTableId> {
        log.info { "Finding route tables in VPC: $vpcId" }

        val describeRequest =
            DescribeRouteTablesRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                ).build()

        val routeTables =
            RetryUtil.withAwsRetry("find-route-tables") {
                ec2Client.describeRouteTables(describeRequest).routeTables()
            }

        // Exclude main route table as it is deleted with the VPC
        val nonMainRouteTables =
            routeTables.filter { rt ->
                rt.associations().none { it.main() }
            }
        val routeTableIds = nonMainRouteTables.map { it.routeTableId() }

        log.info { "Found ${routeTableIds.size} non-main route tables in VPC: $vpcId" }
        return routeTableIds
    }

    // ==================== ENI Methods Implementation ====================

    override fun findActiveNetworkInterfacesInVpc(vpcId: VpcId): List<NetworkInterfaceId> {
        log.info { "Finding active network interfaces in VPC: $vpcId" }

        val describeRequest =
            DescribeNetworkInterfacesRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build(),
                    Filter
                        .builder()
                        .name("status")
                        .values("in-use", "attaching", "detaching")
                        .build(),
                ).build()

        val networkInterfaces =
            RetryUtil.withAwsRetry("find-active-enis") {
                ec2Client.describeNetworkInterfaces(describeRequest).networkInterfaces()
            }
        val eniIds = networkInterfaces.map { it.networkInterfaceId() }

        log.info { "Found ${eniIds.size} active network interfaces in VPC: $vpcId" }
        return eniIds
    }

    override fun waitForNetworkInterfacesCleared(
        vpcId: VpcId,
        timeoutMs: Long,
    ) {
        val activeEnis = findActiveNetworkInterfacesInVpc(vpcId)
        if (activeEnis.isEmpty()) {
            log.info { "No active network interfaces in VPC: $vpcId" }
            return
        }

        log.info { "Waiting for ${activeEnis.size} network interfaces to clear in VPC: $vpcId" }
        outputHandler.handleMessage("Waiting for ${activeEnis.size} network interfaces to clear...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val remaining = findActiveNetworkInterfacesInVpc(vpcId)

            if (remaining.isEmpty()) {
                log.info { "All network interfaces cleared in VPC: $vpcId" }
                outputHandler.handleMessage("All network interfaces cleared")
                return
            }

            log.debug { "Still waiting for ${remaining.size} network interfaces to clear..." }
            Thread.sleep(VpcService.POLL_INTERVAL_MS)
        }

        throw AwsTimeoutException(
            "Timeout waiting for network interfaces to clear in VPC $vpcId after ${timeoutMs}ms",
        )
    }

    // ==================== Deletion Methods Implementation ====================

    override fun terminateInstances(instanceIds: List<InstanceId>) {
        if (instanceIds.isEmpty()) {
            log.info { "No instances to terminate" }
            return
        }

        log.info { "Terminating ${instanceIds.size} instances: $instanceIds" }
        outputHandler.handleMessage("Terminating ${instanceIds.size} EC2 instances...")

        val terminateRequest =
            TerminateInstancesRequest
                .builder()
                .instanceIds(instanceIds)
                .build()

        RetryUtil.withAwsRetry("terminate-instances") { ec2Client.terminateInstances(terminateRequest) }
        log.info { "Initiated termination for instances: $instanceIds" }
    }

    override fun waitForInstancesTerminated(
        instanceIds: List<InstanceId>,
        timeoutMs: Long,
    ) {
        if (instanceIds.isEmpty()) {
            return
        }

        log.info { "Waiting for ${instanceIds.size} instances to terminate..." }
        outputHandler.handleMessage("Waiting for instances to terminate...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val describeRequest =
                DescribeInstancesRequest
                    .builder()
                    .instanceIds(instanceIds)
                    .build()

            val reservations = ec2Client.describeInstances(describeRequest).reservations()
            val instances = reservations.flatMap { it.instances() }

            val allTerminated =
                instances.all { instance ->
                    instance.state().name() == InstanceStateName.TERMINATED
                }

            if (allTerminated) {
                log.info { "All instances terminated successfully" }
                outputHandler.handleMessage("All instances terminated")
                return
            }

            val pending = instances.count { it.state().name() != InstanceStateName.TERMINATED }
            log.debug { "Still waiting for $pending instances to terminate..." }

            Thread.sleep(VpcService.POLL_INTERVAL_MS)
        }

        throw AwsTimeoutException("Timeout waiting for instances to terminate after ${timeoutMs}ms")
    }

    /**
     * Revokes all ingress and egress rules from a security group.
     * This is necessary to break circular dependencies between security groups
     * (e.g., EMR master/slave security groups reference each other).
     */
    override fun revokeSecurityGroupRules(securityGroupId: SecurityGroupId) {
        log.info { "Revoking all rules from security group: $securityGroupId" }

        val describeRequest =
            DescribeSecurityGroupsRequest
                .builder()
                .groupIds(securityGroupId)
                .build()

        val securityGroups = ec2Client.describeSecurityGroups(describeRequest).securityGroups()
        if (securityGroups.isEmpty()) {
            return
        }

        val sg = securityGroups[0]

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
        outputHandler.handleMessage("Deleting security group: $securityGroupId")

        val deleteRequest =
            DeleteSecurityGroupRequest
                .builder()
                .groupId(securityGroupId)
                .build()

        RetryUtil.withVpcTeardownRetry("delete-security-group") { ec2Client.deleteSecurityGroup(deleteRequest) }
        log.info { "Deleted security group: $securityGroupId" }
    }

    override fun detachInternetGateway(
        igwId: InternetGatewayId,
        vpcId: VpcId,
    ) {
        log.info { "Detaching internet gateway $igwId from VPC $vpcId" }
        outputHandler.handleMessage("Detaching internet gateway...")

        val detachRequest =
            DetachInternetGatewayRequest
                .builder()
                .internetGatewayId(igwId)
                .vpcId(vpcId)
                .build()

        RetryUtil.withVpcTeardownRetry("detach-igw") { ec2Client.detachInternetGateway(detachRequest) }
        log.info { "Detached internet gateway $igwId from VPC $vpcId" }
    }

    override fun deleteInternetGateway(igwId: InternetGatewayId) {
        log.info { "Deleting internet gateway: $igwId" }
        outputHandler.handleMessage("Deleting internet gateway: $igwId")

        val deleteRequest =
            DeleteInternetGatewayRequest
                .builder()
                .internetGatewayId(igwId)
                .build()

        RetryUtil.withVpcTeardownRetry("delete-igw") { ec2Client.deleteInternetGateway(deleteRequest) }
        log.info { "Deleted internet gateway: $igwId" }
    }

    override fun deleteSubnet(subnetId: SubnetId) {
        log.info { "Deleting subnet: $subnetId" }
        outputHandler.handleMessage("Deleting subnet: $subnetId")

        val deleteRequest =
            DeleteSubnetRequest
                .builder()
                .subnetId(subnetId)
                .build()

        RetryUtil.withVpcTeardownRetry("delete-subnet") { ec2Client.deleteSubnet(deleteRequest) }
        log.info { "Deleted subnet: $subnetId" }
    }

    override fun deleteNatGateway(natGatewayId: NatGatewayId) {
        log.info { "Deleting NAT gateway: $natGatewayId" }
        outputHandler.handleMessage("Deleting NAT gateway: $natGatewayId")

        val deleteRequest =
            DeleteNatGatewayRequest
                .builder()
                .natGatewayId(natGatewayId)
                .build()

        RetryUtil.withAwsRetry("delete-nat-gateway") { ec2Client.deleteNatGateway(deleteRequest) }
        log.info { "Initiated deletion of NAT gateway: $natGatewayId" }
    }

    override fun waitForNatGatewaysDeleted(
        natGatewayIds: List<NatGatewayId>,
        timeoutMs: Long,
    ) {
        if (natGatewayIds.isEmpty()) {
            return
        }

        log.info { "Waiting for ${natGatewayIds.size} NAT gateways to be deleted..." }
        outputHandler.handleMessage("Waiting for NAT gateways to be deleted...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val describeRequest =
                DescribeNatGatewaysRequest
                    .builder()
                    .natGatewayIds(natGatewayIds)
                    .build()

            val natGateways = ec2Client.describeNatGateways(describeRequest).natGateways()

            val allDeleted =
                natGateways.all { natGateway ->
                    natGateway.state() == NatGatewayState.DELETED
                }

            if (allDeleted) {
                log.info { "All NAT gateways deleted successfully" }
                outputHandler.handleMessage("All NAT gateways deleted")
                return
            }

            val pending = natGateways.count { it.state() != NatGatewayState.DELETED }
            log.debug { "Still waiting for $pending NAT gateways to be deleted..." }

            Thread.sleep(VpcService.POLL_INTERVAL_MS)
        }

        throw AwsTimeoutException("Timeout waiting for NAT gateways to be deleted after ${timeoutMs}ms")
    }

    override fun deleteRouteTable(routeTableId: RouteTableId) {
        log.info { "Deleting route table: $routeTableId" }

        // First, disassociate any subnet associations
        disassociateRouteTableAssociations(routeTableId)

        // Then delete the route table
        outputHandler.handleMessage("Deleting route table: $routeTableId")
        val deleteRequest =
            DeleteRouteTableRequest
                .builder()
                .routeTableId(routeTableId)
                .build()

        RetryUtil.withVpcTeardownRetry("delete-route-table") { ec2Client.deleteRouteTable(deleteRequest) }
        log.info { "Deleted route table: $routeTableId" }
    }

    /**
     * Disassociates all non-main subnet associations from a route table.
     * Main associations cannot be disassociated and are deleted with the VPC.
     */
    private fun disassociateRouteTableAssociations(routeTableId: RouteTableId) {
        val describeRequest =
            DescribeRouteTablesRequest
                .builder()
                .routeTableIds(routeTableId)
                .build()

        val routeTable =
            RetryUtil.withAwsRetry("describe-route-table") {
                ec2Client.describeRouteTables(describeRequest).routeTables().firstOrNull()
            } ?: return

        // Disassociate each non-main association
        routeTable
            .associations()
            .filter { !it.main() }
            .forEach { association ->
                log.info { "Disassociating route table association: ${association.routeTableAssociationId()}" }
                val disassociateRequest =
                    DisassociateRouteTableRequest
                        .builder()
                        .associationId(association.routeTableAssociationId())
                        .build()
                RetryUtil.withAwsRetry("disassociate-route-table") {
                    ec2Client.disassociateRouteTable(disassociateRequest)
                }
            }
    }

    override fun deleteVpc(vpcId: VpcId) {
        log.info { "Deleting VPC: $vpcId" }
        outputHandler.handleMessage("Deleting VPC: $vpcId")

        val deleteRequest =
            DeleteVpcRequest
                .builder()
                .vpcId(vpcId)
                .build()

        RetryUtil.withVpcTeardownRetry("delete-vpc") { ec2Client.deleteVpc(deleteRequest) }
        log.info { "Deleted VPC: $vpcId" }
    }

    // ==================== Security Group Details ====================

    override fun describeSecurityGroup(securityGroupId: String): SecurityGroupDetails {
        log.debug { "Describing security group: $securityGroupId" }

        val request =
            DescribeSecurityGroupsRequest
                .builder()
                .groupIds(securityGroupId)
                .build()

        val response =
            RetryUtil.withAwsRetry("describe-security-group") {
                ec2Client.describeSecurityGroups(request)
            }

        val sg =
            response.securityGroups().firstOrNull()
                ?: error("Security group $securityGroupId not found")

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

    private fun formatProtocol(protocol: String): String =
        when (protocol) {
            "-1" -> "All"
            else -> protocol.uppercase()
        }

    @Suppress("MagicNumber", "CyclomaticComplexity")
    private fun getPortDescription(
        fromPort: Int?,
        toPort: Int?,
    ): String? {
        if (fromPort == null || toPort == null) return "All traffic"
        if (fromPort != toPort) return null

        return when (fromPort) {
            WellKnownPorts.SSH -> "SSH"
            WellKnownPorts.HTTP -> "HTTP"
            WellKnownPorts.HTTPS -> "HTTPS"
            WellKnownPorts.MYSQL -> "MySQL"
            WellKnownPorts.POSTGRESQL -> "PostgreSQL"
            WellKnownPorts.REDIS -> "Redis"
            WellKnownPorts.CASSANDRA_INTERNODE -> "Cassandra Inter-node"
            WellKnownPorts.CASSANDRA_INTERNODE_SSL -> "Cassandra Inter-node SSL"
            WellKnownPorts.CASSANDRA_CQL -> "Cassandra CQL"
            WellKnownPorts.CASSANDRA_CQL_SSL -> "Cassandra CQL SSL"
            WellKnownPorts.CASSANDRA_THRIFT -> "Cassandra Thrift"
            WellKnownPorts.CASSANDRA_JMX -> "Cassandra JMX"
            else -> null
        }
    }
}
