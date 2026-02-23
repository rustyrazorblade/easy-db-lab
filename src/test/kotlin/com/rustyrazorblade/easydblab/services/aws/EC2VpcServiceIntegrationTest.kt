package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.services.aws.EC2VpcService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client

/**
 * Integration tests for EC2VpcService using LocalStack.
 *
 * Tests verify that VPC resource creation, idempotent find-or-create patterns,
 * security group ingress rules, route tables, and tag operations work correctly
 * through real AWS SDK API calls against a LocalStack EC2 endpoint.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EC2VpcServiceIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.EC2)
    }

    private lateinit var ec2Client: Ec2Client
    private lateinit var vpcService: EC2VpcService

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
    }

    @Nested
    inner class CreateVpc {
        @Test
        fun `should create VPC with correct CIDR and tags`() {
            val vpcId = vpcService.createVpc("test-create-vpc", "10.0.0.0/16", mapOf("env" to "test"))

            assertThat(vpcId).startsWith("vpc-")

            val tags = vpcService.getVpcTags(vpcId)
            assertThat(tags["Name"]).isEqualTo("test-create-vpc")
            assertThat(tags["env"]).isEqualTo("test")
        }

        @Test
        fun `should always create new VPC even with same name`() {
            val vpc1 = vpcService.createVpc("test-dup-vpc", "10.0.0.0/16", emptyMap())
            val vpc2 = vpcService.createVpc("test-dup-vpc", "10.1.0.0/16", emptyMap())

            assertThat(vpc1).isNotEqualTo(vpc2)
        }
    }

    @Nested
    inner class FindOrCreateSubnet {
        @Test
        fun `should create subnet when none exists`() {
            val vpcId = vpcService.createVpc("test-subnet-create", "10.0.0.0/16", emptyMap())

            val subnetId =
                vpcService.findOrCreateSubnet(
                    vpcId,
                    "test-subnet",
                    "10.0.1.0/24",
                    emptyMap(),
                )

            assertThat(subnetId).startsWith("subnet-")
        }

        @Test
        fun `should return existing subnet on second call`() {
            val vpcId = vpcService.createVpc("test-subnet-idempotent", "10.0.0.0/16", emptyMap())

            val first = vpcService.findOrCreateSubnet(vpcId, "idem-subnet", "10.0.1.0/24", emptyMap())
            val second = vpcService.findOrCreateSubnet(vpcId, "idem-subnet", "10.0.1.0/24", emptyMap())

            assertThat(first).isEqualTo(second)
        }
    }

    @Nested
    inner class FindOrCreateInternetGateway {
        @Test
        fun `should create and attach IGW`() {
            val vpcId = vpcService.createVpc("test-igw-create", "10.0.0.0/16", emptyMap())

            val igwId = vpcService.findOrCreateInternetGateway(vpcId, "test-igw", emptyMap())

            assertThat(igwId).startsWith("igw-")
            // Verify it's attached by looking it up
            val foundIgw = vpcService.findInternetGatewayByVpc(vpcId)
            assertThat(foundIgw).isEqualTo(igwId)
        }

        @Test
        fun `should return existing IGW on second call`() {
            val vpcId = vpcService.createVpc("test-igw-idempotent", "10.0.0.0/16", emptyMap())

            val first = vpcService.findOrCreateInternetGateway(vpcId, "idem-igw", emptyMap())
            val second = vpcService.findOrCreateInternetGateway(vpcId, "idem-igw", emptyMap())

            assertThat(first).isEqualTo(second)
        }
    }

    @Nested
    inner class FindOrCreateSecurityGroup {
        @Test
        fun `should create security group`() {
            val vpcId = vpcService.createVpc("test-sg-create", "10.0.0.0/16", emptyMap())

            val sgId =
                vpcService.findOrCreateSecurityGroup(
                    vpcId,
                    "test-sg",
                    "Test SG",
                    emptyMap(),
                )

            assertThat(sgId).startsWith("sg-")
        }

        @Test
        fun `should return existing SG on second call`() {
            val vpcId = vpcService.createVpc("test-sg-idempotent", "10.0.0.0/16", emptyMap())

            val first = vpcService.findOrCreateSecurityGroup(vpcId, "idem-sg", "Test", emptyMap())
            val second = vpcService.findOrCreateSecurityGroup(vpcId, "idem-sg", "Test", emptyMap())

            assertThat(first).isEqualTo(second)
        }
    }

    @Nested
    inner class AuthorizeSecurityGroupIngress {
        @Test
        fun `should add ingress rule and verify via describe`() {
            val vpcId = vpcService.createVpc("test-sg-ingress", "10.0.0.0/16", emptyMap())
            val sgId = vpcService.findOrCreateSecurityGroup(vpcId, "ingress-sg", "Test", emptyMap())

            vpcService.authorizeSecurityGroupIngress(sgId, 22, 22, "0.0.0.0/0", "tcp")

            val details = vpcService.describeSecurityGroup(sgId)
            assertThat(details.inboundRules).anyMatch {
                it.fromPort == 22 && it.toPort == 22 && it.protocol == "TCP"
            }
        }

        @Test
        fun `should be idempotent when adding same rule twice`() {
            val vpcId = vpcService.createVpc("test-sg-ingress-idem", "10.0.0.0/16", emptyMap())
            val sgId = vpcService.findOrCreateSecurityGroup(vpcId, "ingress-idem-sg", "Test", emptyMap())

            vpcService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")
            // Second call should not throw
            vpcService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")

            val details = vpcService.describeSecurityGroup(sgId)
            val matchingRules =
                details.inboundRules.filter {
                    it.fromPort == 9042 && it.toPort == 9042
                }
            assertThat(matchingRules).hasSize(1)
        }

        @Test
        fun `should add multiple different rules`() {
            val vpcId = vpcService.createVpc("test-sg-multi-rules", "10.0.0.0/16", emptyMap())
            val sgId = vpcService.findOrCreateSecurityGroup(vpcId, "multi-rules-sg", "Test", emptyMap())

            vpcService.authorizeSecurityGroupIngress(sgId, 22, 22, "0.0.0.0/0", "tcp")
            vpcService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")

            val details = vpcService.describeSecurityGroup(sgId)
            assertThat(details.inboundRules).hasSize(2)
        }
    }

    @Nested
    inner class EnsureRouteTable {
        @Test
        fun `should create default route to internet gateway`() {
            val vpcId = vpcService.createVpc("test-route", "10.0.0.0/16", emptyMap())
            val subnetId = vpcService.findOrCreateSubnet(vpcId, "route-subnet", "10.0.1.0/24", emptyMap())
            val igwId = vpcService.findOrCreateInternetGateway(vpcId, "route-igw", emptyMap())

            // Should not throw
            vpcService.ensureRouteTable(vpcId, subnetId, igwId)
        }

        @Test
        fun `should be idempotent when called twice`() {
            val vpcId = vpcService.createVpc("test-route-idem", "10.0.0.0/16", emptyMap())
            val subnetId = vpcService.findOrCreateSubnet(vpcId, "route-idem-subnet", "10.0.1.0/24", emptyMap())
            val igwId = vpcService.findOrCreateInternetGateway(vpcId, "route-idem-igw", emptyMap())

            vpcService.ensureRouteTable(vpcId, subnetId, igwId)
            // Second call should not throw
            vpcService.ensureRouteTable(vpcId, subnetId, igwId)
        }
    }

    @Nested
    inner class VpcTags {
        @Test
        fun `should return all tags from VPC`() {
            val vpcId =
                vpcService.createVpc(
                    "test-tags",
                    "10.0.0.0/16",
                    mapOf("ClusterId" to "abc123", "Environment" to "test"),
                )

            val tags = vpcService.getVpcTags(vpcId)

            assertThat(tags["Name"]).isEqualTo("test-tags")
            assertThat(tags["ClusterId"]).isEqualTo("abc123")
            assertThat(tags["Environment"]).isEqualTo("test")
        }

        @Test
        fun `should throw when VPC not found`() {
            assertThatThrownBy {
                vpcService.getVpcTags("vpc-nonexistent")
            }.isInstanceOf(Exception::class.java)
        }

        @Test
        fun `should add tags to existing VPC`() {
            val vpcId = vpcService.createVpc("test-add-tags", "10.0.0.0/16", emptyMap())

            vpcService.addTagsToVpc(vpcId, mapOf("NewTag" to "new-value"))

            val tags = vpcService.getVpcTags(vpcId)
            assertThat(tags["NewTag"]).isEqualTo("new-value")
        }
    }

    @Nested
    inner class FindVpc {
        @Test
        fun `should find VPC by name`() {
            val vpcId = vpcService.createVpc("test-find-by-name", "10.0.0.0/16", emptyMap())

            val found = vpcService.findVpcByName("test-find-by-name")

            assertThat(found).isEqualTo(vpcId)
        }

        @Test
        fun `should return null when VPC name not found`() {
            assertThat(vpcService.findVpcByName("nonexistent-vpc-name")).isNull()
        }

        @Test
        fun `should find VPCs by tag`() {
            val vpcId =
                vpcService.createVpc(
                    "test-find-by-tag",
                    "10.0.0.0/16",
                    mapOf("SearchTag" to "search-value"),
                )

            val found = vpcService.findVpcsByTag("SearchTag", "search-value")

            assertThat(found).contains(vpcId)
        }

        @Test
        fun `should get VPC name`() {
            val vpcId = vpcService.createVpc("test-get-name", "10.0.0.0/16", emptyMap())

            assertThat(vpcService.getVpcName(vpcId)).isEqualTo("test-get-name")
        }
    }
}
