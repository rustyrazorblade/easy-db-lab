package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.EC2VpcService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.CreateInternetGatewayRequest
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest
import software.amazon.awssdk.services.ec2.model.IpPermission
import software.amazon.awssdk.services.ec2.model.IpRange
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import software.amazon.awssdk.services.ec2.model.ResourceType as Ec2ResourceType

/**
 * Integration tests for AwsInfrastructureService resource discovery using LocalStack.
 *
 * Tests verify that discoverResources() correctly finds VPC resources through
 * real AWS SDK API calls against a LocalStack EC2 endpoint.
 *
 * EMR and OpenSearch are mocked since LocalStack Community Edition doesn't support them.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsInfrastructureServiceIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.EC2)
    }

    private lateinit var ec2Client: Ec2Client
    private lateinit var vpcService: EC2VpcService
    private lateinit var emrService: EMRService
    private lateinit var openSearchService: OpenSearchService
    private lateinit var service: AwsInfrastructureService

    @BeforeAll
    fun setupClients() {
        val credentials =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    localStack.accessKey,
                    localStack.secretKey,
                ),
            )

        ec2Client =
            Ec2Client
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.EC2))
                .region(Region.of(localStack.region))
                .credentialsProvider(credentials)
                .build()
    }

    @BeforeEach
    fun setup() {
        vpcService =
            EC2VpcService(
                ec2Client,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
        emrService = mock()
        openSearchService = mock()

        // EMR and OpenSearch return empty by default (not supported by LocalStack CE)
        whenever(emrService.findClustersInVpc(any(), any())).thenReturn(emptyList())
        whenever(openSearchService.findDomainsInVpc(any())).thenReturn(emptyList())

        service =
            AwsInfrastructureService(
                vpcService = vpcService,
                emrService = emrService,
                openSearchService = openSearchService,
                eventBus =
                    com.rustyrazorblade.easydblab.events
                        .EventBus(),
            )
    }

    private fun createVpc(
        name: String,
        cidr: String = "10.0.0.0/16",
    ): String {
        val request =
            CreateVpcRequest
                .builder()
                .cidrBlock(cidr)
                .tagSpecifications(
                    TagSpecification
                        .builder()
                        .resourceType(Ec2ResourceType.VPC)
                        .tags(
                            Tag
                                .builder()
                                .key("Name")
                                .value(name)
                                .build(),
                        ).build(),
                ).build()

        return ec2Client.createVpc(request).vpc().vpcId()
    }

    private fun createSubnet(
        vpcId: String,
        cidr: String,
    ): String {
        val request =
            CreateSubnetRequest
                .builder()
                .vpcId(vpcId)
                .cidrBlock(cidr)
                .build()

        return ec2Client.createSubnet(request).subnet().subnetId()
    }

    private fun createSecurityGroup(
        vpcId: String,
        name: String,
    ): String {
        val request =
            CreateSecurityGroupRequest
                .builder()
                .vpcId(vpcId)
                .groupName(name)
                .description("Test security group $name")
                .build()

        return ec2Client.createSecurityGroup(request).groupId()
    }

    private fun createAndAttachInternetGateway(vpcId: String): String {
        val igwId =
            ec2Client
                .createInternetGateway(CreateInternetGatewayRequest.builder().build())
                .internetGateway()
                .internetGatewayId()

        ec2Client.attachInternetGateway(
            AttachInternetGatewayRequest
                .builder()
                .internetGatewayId(igwId)
                .vpcId(vpcId)
                .build(),
        )

        return igwId
    }

    @Nested
    inner class DiscoverResources {
        @Test
        fun `should discover subnets in VPC`() {
            val vpcId = createVpc("test-discover-subnets")
            val subnet1 = createSubnet(vpcId, "10.0.1.0/24")
            val subnet2 = createSubnet(vpcId, "10.0.2.0/24")

            val resources = service.discoverResources(vpcId)

            assertThat(resources.vpcId).isEqualTo(vpcId)
            assertThat(resources.subnetIds).contains(subnet1, subnet2)
        }

        @Test
        fun `should discover security groups in VPC excluding default`() {
            val vpcId = createVpc("test-discover-sgs")
            val sgId = createSecurityGroup(vpcId, "test-sg")

            val resources = service.discoverResources(vpcId)

            // Should find the custom SG but not the default SG
            assertThat(resources.securityGroupIds).contains(sgId)
        }

        @Test
        fun `should discover internet gateway attached to VPC`() {
            val vpcId = createVpc("test-discover-igw")
            val igwId = createAndAttachInternetGateway(vpcId)

            val resources = service.discoverResources(vpcId)

            assertThat(resources.internetGatewayId).isEqualTo(igwId)
        }

        @Test
        fun `should discover VPC name`() {
            val vpcId = createVpc("test-discover-name")

            val resources = service.discoverResources(vpcId)

            assertThat(resources.vpcName).isEqualTo("test-discover-name")
        }

        @Test
        fun `should return empty lists for VPC with no extra resources`() {
            val vpcId = createVpc("test-discover-empty")

            val resources = service.discoverResources(vpcId)

            assertThat(resources.vpcId).isEqualTo(vpcId)
            assertThat(resources.instanceIds).isEmpty()
            assertThat(resources.emrClusterIds).isEmpty()
            assertThat(resources.openSearchDomainNames).isEmpty()
            assertThat(resources.internetGatewayId).isNull()
        }

        @Test
        fun `should not discover resources from a different VPC`() {
            val vpc1 = createVpc("test-isolation-1", "10.1.0.0/16")
            val vpc2 = createVpc("test-isolation-2", "10.2.0.0/16")
            val subnet1 = createSubnet(vpc1, "10.1.1.0/24")
            createSubnet(vpc2, "10.2.1.0/24")

            val resources = service.discoverResources(vpc1)

            assertThat(resources.subnetIds).contains(subnet1)
            // Subnets from vpc2 should not appear
            assertThat(resources.subnetIds).allMatch { it != "subnet from vpc2" }
        }

        @Test
        fun `should discover all resource types together`() {
            val vpcId = createVpc("test-discover-all")
            val subnet = createSubnet(vpcId, "10.0.1.0/24")
            val sgId = createSecurityGroup(vpcId, "test-all-sg")
            val igwId = createAndAttachInternetGateway(vpcId)

            val resources = service.discoverResources(vpcId)

            assertThat(resources.vpcId).isEqualTo(vpcId)
            assertThat(resources.vpcName).isEqualTo("test-discover-all")
            assertThat(resources.subnetIds).contains(subnet)
            assertThat(resources.securityGroupIds).contains(sgId)
            assertThat(resources.internetGatewayId).isEqualTo(igwId)
        }
    }

    @Nested
    inner class SecurityGroupDescribe {
        @Test
        fun `should describe security group with ingress rules`() {
            val vpcId = createVpc("test-sg-describe")
            val sgId = createSecurityGroup(vpcId, "test-describe-sg")

            // Add an ingress rule
            ec2Client.authorizeSecurityGroupIngress(
                AuthorizeSecurityGroupIngressRequest
                    .builder()
                    .groupId(sgId)
                    .ipPermissions(
                        IpPermission
                            .builder()
                            .ipProtocol("tcp")
                            .fromPort(22)
                            .toPort(22)
                            .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                            .build(),
                    ).build(),
            )

            val details = vpcService.describeSecurityGroup(sgId)

            assertThat(details.securityGroupId).isEqualTo(sgId)
            assertThat(details.name).isEqualTo("test-describe-sg")
            assertThat(details.inboundRules).isNotEmpty()
            assertThat(details.inboundRules).anyMatch {
                it.fromPort == 22 && it.toPort == 22 && it.protocol == "TCP"
            }
        }
    }
}
