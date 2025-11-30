package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class AwsInfrastructureServiceTest {
    private val mockVpcService: VpcService = mock()
    private val mockOutputHandler: OutputHandler = mock()
    private val awsInfraService = AwsInfrastructureService(mockVpcService, mockOutputHandler)

    @Test
    fun `ensurePackerInfrastructure should create all required resources in correct order`() {
        // Mock VPC creation
        whenever(
            mockVpcService.findOrCreateVpc(
                eq("easy-cass-lab-packer"),
                eq("10.0.0.0/16"),
                eq(mapOf("easy_cass_lab" to "1")),
            ),
        ).thenReturn("vpc-12345")

        // Mock Internet Gateway creation
        whenever(
            mockVpcService.findOrCreateInternetGateway(
                eq("vpc-12345"),
                eq("easy-cass-lab-packer-igw"),
                eq(mapOf("easy_cass_lab" to "1")),
            ),
        ).thenReturn("igw-12345")

        // Mock Subnet creation
        whenever(
            mockVpcService.findOrCreateSubnet(
                eq("vpc-12345"),
                eq("easy-cass-lab-packer-subnet"),
                eq("10.0.1.0/24"),
                eq(mapOf("easy_cass_lab" to "1")),
                anyOrNull(),
            ),
        ).thenReturn("subnet-12345")

        // Mock Security Group creation
        whenever(
            mockVpcService.findOrCreateSecurityGroup(
                eq("vpc-12345"),
                eq("easy-cass-lab-packer-sg"),
                eq("Security group for Packer AMI builds"),
                eq(mapOf("easy_cass_lab" to "1")),
            ),
        ).thenReturn("sg-12345")

        val result = awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        assertThat(result.vpcId).isEqualTo("vpc-12345")
        assertThat(result.subnetId).isEqualTo("subnet-12345")
        assertThat(result.securityGroupId).isEqualTo("sg-12345")
        assertThat(result.internetGatewayId).isEqualTo("igw-12345")
    }

    @Test
    fun `ensurePackerInfrastructure should call VpcService methods in correct order`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val inOrder = inOrder(mockVpcService)

        // VPC must be created first
        inOrder.verify(mockVpcService).findOrCreateVpc(any(), any(), any())

        // Internet gateway must be created after VPC
        inOrder.verify(mockVpcService).findOrCreateInternetGateway(any(), any(), any())

        // Subnet must be created after VPC
        inOrder.verify(mockVpcService).findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())

        // Route table must be configured after VPC, subnet, and IGW exist
        inOrder.verify(mockVpcService).ensureRouteTable(any(), any(), any())

        // Security group must be created after VPC
        inOrder.verify(mockVpcService).findOrCreateSecurityGroup(any(), any(), any(), any())

        // Security group ingress must be configured after security group exists
        inOrder.verify(mockVpcService).authorizeSecurityGroupIngress(any(), any(), any(), any(), any())
    }

    @Test
    fun `ensurePackerInfrastructure should configure route table with correct VPC, subnet, and IGW IDs`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val inOrder = inOrder(mockVpcService)
        inOrder.verify(mockVpcService).ensureRouteTable(
            eq("vpc-12345"),
            eq("subnet-12345"),
            eq("igw-12345"),
        )
    }

    @Test
    fun `ensurePackerInfrastructure should configure security group ingress for SSH`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val inOrder = inOrder(mockVpcService)
        inOrder.verify(mockVpcService).authorizeSecurityGroupIngress(
            eq("sg-12345"),
            eq(Constants.Network.SSH_PORT),
            eq(Constants.Network.SSH_PORT),
            eq("0.0.0.0/0"),
            eq("tcp"),
        )
    }

    @Test
    fun `ensurePackerInfrastructure should use correct resource names`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val inOrder = inOrder(mockVpcService)

        inOrder.verify(mockVpcService).findOrCreateVpc(
            eq("easy-cass-lab-packer"),
            any(),
            any(),
        )

        inOrder.verify(mockVpcService).findOrCreateInternetGateway(
            any(),
            eq("easy-cass-lab-packer-igw"),
            any(),
        )

        inOrder.verify(mockVpcService).findOrCreateSubnet(
            any(),
            eq("easy-cass-lab-packer-subnet"),
            any(),
            any(),
            anyOrNull(),
        )

        inOrder.verify(mockVpcService).findOrCreateSecurityGroup(
            any(),
            eq("easy-cass-lab-packer-sg"),
            any(),
            any(),
        )
    }

    @Test
    fun `ensurePackerInfrastructure should use correct CIDR blocks`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val inOrder = inOrder(mockVpcService)

        inOrder.verify(mockVpcService).findOrCreateVpc(
            any(),
            eq("10.0.0.0/16"),
            any(),
        )

        inOrder.verify(mockVpcService).findOrCreateSubnet(
            any(),
            any(),
            eq("10.0.1.0/24"),
            any(),
            anyOrNull(),
        )
    }

    @Test
    fun `ensurePackerInfrastructure should tag all resources with easy_cass_lab tag`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        val expectedTags = mapOf("easy_cass_lab" to "1")

        val inOrder = inOrder(mockVpcService)

        inOrder.verify(mockVpcService).findOrCreateVpc(
            any(),
            any(),
            eq(expectedTags),
        )

        inOrder.verify(mockVpcService).findOrCreateInternetGateway(
            any(),
            any(),
            eq(expectedTags),
        )

        inOrder.verify(mockVpcService).findOrCreateSubnet(
            any(),
            any(),
            any(),
            eq(expectedTags),
            anyOrNull(),
        )

        inOrder.verify(mockVpcService).findOrCreateSecurityGroup(
            any(),
            any(),
            any(),
            eq(expectedTags),
        )
    }

    @Test
    fun `ensurePackerInfrastructure should be idempotent`() {
        whenever(mockVpcService.findOrCreateVpc(any(), any(), any())).thenReturn("vpc-12345")
        whenever(mockVpcService.findOrCreateInternetGateway(any(), any(), any())).thenReturn("igw-12345")
        whenever(mockVpcService.findOrCreateSubnet(any(), any(), any(), any(), anyOrNull())).thenReturn("subnet-12345")
        whenever(mockVpcService.findOrCreateSecurityGroup(any(), any(), any(), any())).thenReturn("sg-12345")

        // Call twice - should produce same result
        val result1 = awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)
        val result2 = awsInfraService.ensurePackerInfrastructure(Constants.Network.SSH_PORT)

        assertThat(result1).isEqualTo(result2)
    }
}
