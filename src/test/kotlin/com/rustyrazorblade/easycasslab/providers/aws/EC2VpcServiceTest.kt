package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayResponse
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressResponse
import software.amazon.awssdk.services.ec2.model.CreateInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.CreateInternetGatewayResponse
import software.amazon.awssdk.services.ec2.model.CreateRouteRequest
import software.amazon.awssdk.services.ec2.model.CreateRouteResponse
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest
import software.amazon.awssdk.services.ec2.model.CreateSubnetResponse
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest
import software.amazon.awssdk.services.ec2.model.CreateVpcResponse
import software.amazon.awssdk.services.ec2.model.DescribeInternetGatewaysRequest
import software.amazon.awssdk.services.ec2.model.DescribeInternetGatewaysResponse
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesResponse
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.InternetGateway
import software.amazon.awssdk.services.ec2.model.InternetGatewayAttachment
import software.amazon.awssdk.services.ec2.model.IpPermission
import software.amazon.awssdk.services.ec2.model.IpRange
import software.amazon.awssdk.services.ec2.model.Route
import software.amazon.awssdk.services.ec2.model.RouteTable
import software.amazon.awssdk.services.ec2.model.SecurityGroup
import software.amazon.awssdk.services.ec2.model.Subnet
import software.amazon.awssdk.services.ec2.model.Vpc

internal class EC2VpcServiceTest {
    private val mockEc2Client: Ec2Client = mock()
    private val mockOutputHandler: OutputHandler = mock()
    private val vpcService = EC2VpcService(mockEc2Client, mockOutputHandler)

    @Test
    fun `findOrCreateVpc should return existing VPC when found`() {
        val existingVpc =
            Vpc
                .builder()
                .vpcId("vpc-12345")
                .build()

        val describeResponse =
            DescribeVpcsResponse
                .builder()
                .vpcs(existingVpc)
                .build()

        whenever(mockEc2Client.describeVpcs(any<DescribeVpcsRequest>())).thenReturn(describeResponse)

        val result = vpcService.findOrCreateVpc("test-vpc", "10.0.0.0/16", mapOf("env" to "test"))

        assertThat(result).isEqualTo("vpc-12345")
        verify(mockEc2Client, never()).createVpc(any<CreateVpcRequest>())
    }

    @Test
    fun `findOrCreateVpc should create new VPC when not found`() {
        val emptyDescribeResponse =
            DescribeVpcsResponse
                .builder()
                .vpcs(emptyList())
                .build()

        val createVpcResponse =
            CreateVpcResponse
                .builder()
                .vpc(
                    Vpc
                        .builder()
                        .vpcId("vpc-new123")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeVpcs(any<DescribeVpcsRequest>())).thenReturn(emptyDescribeResponse)
        whenever(mockEc2Client.createVpc(any<CreateVpcRequest>())).thenReturn(createVpcResponse)

        val result = vpcService.findOrCreateVpc("test-vpc", "10.0.0.0/16", mapOf("env" to "test"))

        assertThat(result).isEqualTo("vpc-new123")

        val vpcCaptor = argumentCaptor<CreateVpcRequest>()
        verify(mockEc2Client).createVpc(vpcCaptor.capture())
        assertThat(vpcCaptor.firstValue.cidrBlock()).isEqualTo("10.0.0.0/16")
    }

    @Test
    fun `findOrCreateVpc should tag VPC with Name and custom tags`() {
        val emptyDescribeResponse =
            DescribeVpcsResponse
                .builder()
                .vpcs(emptyList())
                .build()

        val createVpcResponse =
            CreateVpcResponse
                .builder()
                .vpc(
                    Vpc
                        .builder()
                        .vpcId("vpc-new123")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeVpcs(any<DescribeVpcsRequest>())).thenReturn(emptyDescribeResponse)
        whenever(mockEc2Client.createVpc(any<CreateVpcRequest>())).thenReturn(createVpcResponse)

        vpcService.findOrCreateVpc("test-vpc", "10.0.0.0/16", mapOf("env" to "test"))

        val vpcCaptor = argumentCaptor<CreateVpcRequest>()
        verify(mockEc2Client).createVpc(vpcCaptor.capture())

        val tagSpecs = vpcCaptor.firstValue.tagSpecifications()
        assertThat(tagSpecs).hasSize(1)
        assertThat(tagSpecs[0].resourceTypeAsString()).isEqualTo("vpc")

        val tags = tagSpecs[0].tags()
        assertThat(tags).hasSize(2)
        assertThat(tags.map { it.key() }).containsExactlyInAnyOrder("Name", "env")
        assertThat(tags.find { it.key() == "Name" }?.value()).isEqualTo("test-vpc")
        assertThat(tags.find { it.key() == "env" }?.value()).isEqualTo("test")
    }

    @Test
    fun `findOrCreateSubnet should return existing subnet when found`() {
        val existingSubnet =
            Subnet
                .builder()
                .subnetId("subnet-12345")
                .build()

        val describeResponse =
            DescribeSubnetsResponse
                .builder()
                .subnets(existingSubnet)
                .build()

        whenever(mockEc2Client.describeSubnets(any<DescribeSubnetsRequest>())).thenReturn(describeResponse)

        val result =
            vpcService.findOrCreateSubnet(
                "vpc-12345",
                "test-subnet",
                "10.0.1.0/24",
                mapOf("env" to "test"),
            )

        assertThat(result).isEqualTo("subnet-12345")
        verify(mockEc2Client, never()).createSubnet(any<CreateSubnetRequest>())
    }

    @Test
    fun `findOrCreateSubnet should create new subnet when not found`() {
        val emptyDescribeResponse =
            DescribeSubnetsResponse
                .builder()
                .subnets(emptyList())
                .build()

        val createSubnetResponse =
            CreateSubnetResponse
                .builder()
                .subnet(
                    Subnet
                        .builder()
                        .subnetId("subnet-new123")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeSubnets(any<DescribeSubnetsRequest>())).thenReturn(emptyDescribeResponse)
        whenever(mockEc2Client.createSubnet(any<CreateSubnetRequest>())).thenReturn(createSubnetResponse)

        val result =
            vpcService.findOrCreateSubnet(
                "vpc-12345",
                "test-subnet",
                "10.0.1.0/24",
                mapOf("env" to "test"),
            )

        assertThat(result).isEqualTo("subnet-new123")

        val subnetCaptor = argumentCaptor<CreateSubnetRequest>()
        verify(mockEc2Client).createSubnet(subnetCaptor.capture())
        assertThat(subnetCaptor.firstValue.vpcId()).isEqualTo("vpc-12345")
        assertThat(subnetCaptor.firstValue.cidrBlock()).isEqualTo("10.0.1.0/24")
    }

    @Test
    fun `findOrCreateInternetGateway should return existing IGW when found`() {
        val existingIgw =
            InternetGateway
                .builder()
                .internetGatewayId("igw-12345")
                .attachments(
                    InternetGatewayAttachment
                        .builder()
                        .vpcId("vpc-12345")
                        .build(),
                ).build()

        val describeResponse =
            DescribeInternetGatewaysResponse
                .builder()
                .internetGateways(existingIgw)
                .build()

        whenever(mockEc2Client.describeInternetGateways(any<DescribeInternetGatewaysRequest>())).thenReturn(
            describeResponse,
        )

        val result = vpcService.findOrCreateInternetGateway("vpc-12345", "test-igw", mapOf("env" to "test"))

        assertThat(result).isEqualTo("igw-12345")
        verify(mockEc2Client, never()).createInternetGateway(any<CreateInternetGatewayRequest>())
    }

    @Test
    fun `findOrCreateInternetGateway should create and attach new IGW when not found`() {
        val emptyDescribeResponse =
            DescribeInternetGatewaysResponse
                .builder()
                .internetGateways(emptyList())
                .build()

        val createIgwResponse =
            CreateInternetGatewayResponse
                .builder()
                .internetGateway(
                    InternetGateway
                        .builder()
                        .internetGatewayId("igw-new123")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeInternetGateways(any<DescribeInternetGatewaysRequest>())).thenReturn(
            emptyDescribeResponse,
        )
        whenever(mockEc2Client.createInternetGateway(any<CreateInternetGatewayRequest>())).thenReturn(
            createIgwResponse,
        )
        whenever(mockEc2Client.attachInternetGateway(any<AttachInternetGatewayRequest>())).thenReturn(
            AttachInternetGatewayResponse.builder().build(),
        )

        val result = vpcService.findOrCreateInternetGateway("vpc-12345", "test-igw", mapOf("env" to "test"))

        assertThat(result).isEqualTo("igw-new123")

        val attachCaptor = argumentCaptor<AttachInternetGatewayRequest>()
        verify(mockEc2Client).attachInternetGateway(attachCaptor.capture())
        assertThat(attachCaptor.firstValue.internetGatewayId()).isEqualTo("igw-new123")
        assertThat(attachCaptor.firstValue.vpcId()).isEqualTo("vpc-12345")
    }

    @Test
    fun `findOrCreateSecurityGroup should return existing SG when found`() {
        val existingSg =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .build()

        val describeResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(existingSg)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            describeResponse,
        )

        val result =
            vpcService.findOrCreateSecurityGroup(
                "vpc-12345",
                "test-sg",
                "Test security group",
                mapOf("env" to "test"),
            )

        assertThat(result).isEqualTo("sg-12345")
        verify(mockEc2Client, never()).createSecurityGroup(any<CreateSecurityGroupRequest>())
    }

    @Test
    fun `findOrCreateSecurityGroup should create new SG when not found`() {
        val emptyDescribeResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(emptyList())
                .build()

        val createSgResponse =
            CreateSecurityGroupResponse
                .builder()
                .groupId("sg-new123")
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            emptyDescribeResponse,
        )
        whenever(mockEc2Client.createSecurityGroup(any<CreateSecurityGroupRequest>())).thenReturn(createSgResponse)

        val result =
            vpcService.findOrCreateSecurityGroup(
                "vpc-12345",
                "test-sg",
                "Test security group",
                mapOf("env" to "test"),
            )

        assertThat(result).isEqualTo("sg-new123")

        val sgCaptor = argumentCaptor<CreateSecurityGroupRequest>()
        verify(mockEc2Client).createSecurityGroup(sgCaptor.capture())
        assertThat(sgCaptor.firstValue.groupName()).isEqualTo("test-sg")
        assertThat(sgCaptor.firstValue.description()).isEqualTo("Test security group")
        assertThat(sgCaptor.firstValue.vpcId()).isEqualTo("vpc-12345")
    }

    @Test
    fun `ensureRouteTable should skip route creation if route already exists`() {
        val existingRoute =
            Route
                .builder()
                .destinationCidrBlock("0.0.0.0/0")
                .gatewayId("igw-12345")
                .build()

        val routeTable =
            RouteTable
                .builder()
                .routeTableId("rtb-12345")
                .routes(existingRoute)
                .build()

        val describeResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(routeTable)
                .build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(describeResponse)

        vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")

        verify(mockEc2Client, never()).createRoute(any<CreateRouteRequest>())
    }

    @Test
    fun `ensureRouteTable should create route if it does not exist`() {
        val routeTable =
            RouteTable
                .builder()
                .routeTableId("rtb-12345")
                .routes(emptyList())
                .build()

        val describeResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(routeTable)
                .build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(describeResponse)
        whenever(mockEc2Client.createRoute(any<CreateRouteRequest>())).thenReturn(
            CreateRouteResponse.builder().build(),
        )

        vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")

        val routeCaptor = argumentCaptor<CreateRouteRequest>()
        verify(mockEc2Client).createRoute(routeCaptor.capture())
        assertThat(routeCaptor.firstValue.routeTableId()).isEqualTo("rtb-12345")
        assertThat(routeCaptor.firstValue.destinationCidrBlock()).isEqualTo("0.0.0.0/0")
        assertThat(routeCaptor.firstValue.gatewayId()).isEqualTo("igw-12345")
    }

    @Test
    fun `ensureRouteTable should throw exception when no main route table found`() {
        val emptyResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(emptyList())
                .build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(emptyResponse)

        assertThrows<IllegalStateException> {
            vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")
        }
    }

    @Test
    fun `authorizeSecurityGroupIngress should skip if rule already exists`() {
        val existingPermission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(22)
                .toPort(22)
                .ipRanges(
                    IpRange
                        .builder()
                        .cidrIp("0.0.0.0/0")
                        .build(),
                ).build()

        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .ipPermissions(existingPermission)
                .build()

        val describeResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            describeResponse,
        )

        vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")

        verify(mockEc2Client, never()).authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())
    }

    @Test
    fun `authorizeSecurityGroupIngress should add rule if it does not exist`() {
        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .ipPermissions(emptyList())
                .build()

        val describeResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            describeResponse,
        )
        whenever(mockEc2Client.authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())).thenReturn(
            AuthorizeSecurityGroupIngressResponse.builder().build(),
        )

        vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")

        val authCaptor = argumentCaptor<AuthorizeSecurityGroupIngressRequest>()
        verify(mockEc2Client).authorizeSecurityGroupIngress(authCaptor.capture())
        assertThat(authCaptor.firstValue.groupId()).isEqualTo("sg-12345")

        val permission = authCaptor.firstValue.ipPermissions()[0]
        assertThat(permission.ipProtocol()).isEqualTo("tcp")
        assertThat(permission.fromPort()).isEqualTo(22)
        assertThat(permission.toPort()).isEqualTo(22)
        assertThat(permission.ipRanges()[0].cidrIp()).isEqualTo("0.0.0.0/0")
    }

    @Test
    fun `authorizeSecurityGroupIngress should throw exception if security group not found`() {
        val emptyResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(emptyList())
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            emptyResponse,
        )

        assertThrows<IllegalArgumentException> {
            vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")
        }
    }

    @Test
    fun `authorizeSecurityGroupIngress should handle duplicate permission exception`() {
        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .ipPermissions(emptyList())
                .build()

        val describeResponse =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        val ec2Exception =
            Ec2Exception
                .builder()
                .awsErrorDetails(
                    software.amazon.awssdk.awscore.exception.AwsErrorDetails
                        .builder()
                        .errorCode("InvalidPermission.Duplicate")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            describeResponse,
        )
        whenever(mockEc2Client.authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())).thenThrow(
            ec2Exception,
        )

        // Should not throw exception - should handle duplicate gracefully
        vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")

        verify(mockEc2Client).authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())
    }

    @Test
    fun `ensureRouteTable should handle route already exists exception`() {
        val routeTable =
            RouteTable
                .builder()
                .routeTableId("rtb-12345")
                .routes(emptyList())
                .build()

        val describeResponse =
            DescribeRouteTablesResponse
                .builder()
                .routeTables(routeTable)
                .build()

        val ec2Exception =
            Ec2Exception
                .builder()
                .awsErrorDetails(
                    software.amazon.awssdk.awscore.exception.AwsErrorDetails
                        .builder()
                        .errorCode("RouteAlreadyExists")
                        .build(),
                ).build()

        whenever(mockEc2Client.describeRouteTables(any<DescribeRouteTablesRequest>())).thenReturn(describeResponse)
        whenever(mockEc2Client.createRoute(any<CreateRouteRequest>())).thenThrow(ec2Exception)

        // Should not throw exception - should handle duplicate gracefully
        vpcService.ensureRouteTable("vpc-12345", "subnet-12345", "igw-12345")

        verify(mockEc2Client).createRoute(any<CreateRouteRequest>())
    }
}
