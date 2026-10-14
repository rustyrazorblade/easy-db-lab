package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.ec2.Ec2Client

/**
 * Integration tests for [EC2SecurityGroupService] using LocalStack.
 *
 * Tests verify the find-or-create pattern and ingress rules through real AWS SDK calls
 * against the JVM-wide [SharedLocalStack] EC2 backend.  Each test creates its own VPC, so
 * co-tenant EC2 resources cannot affect the results.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EC2SecurityGroupServiceIntegrationTest {
    private lateinit var ec2Client: Ec2Client
    private lateinit var vpcService: EC2VpcService
    private lateinit var securityGroupService: EC2SecurityGroupService

    @BeforeAll
    fun setupClients() {
        ec2Client = SharedLocalStack.ec2Client()
    }

    @BeforeEach
    fun setup() {
        val eventBus = EventBus()
        vpcService = EC2VpcService(ec2Client, eventBus)
        securityGroupService = EC2SecurityGroupService(ec2Client, eventBus)
    }

    @Nested
    inner class FindOrCreateSecurityGroup {
        @Test
        fun `should create security group`() {
            val vpcId = vpcService.createVpc("test-sg-create", "10.0.0.0/16", emptyMap())

            val sgId =
                securityGroupService.findOrCreateSecurityGroup(
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

            val first = securityGroupService.findOrCreateSecurityGroup(vpcId, "idem-sg", "Test", emptyMap())
            val second = securityGroupService.findOrCreateSecurityGroup(vpcId, "idem-sg", "Test", emptyMap())

            assertThat(first).isEqualTo(second)
        }
    }

    @Nested
    inner class AuthorizeSecurityGroupIngress {
        @Test
        fun `should add ingress rule and verify via describe`() {
            val vpcId = vpcService.createVpc("test-sg-ingress", "10.0.0.0/16", emptyMap())
            val sgId = securityGroupService.findOrCreateSecurityGroup(vpcId, "ingress-sg", "Test", emptyMap())

            securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "0.0.0.0/0", "tcp")

            val details = securityGroupService.describeSecurityGroup(sgId)
            assertThat(details.inboundRules).anyMatch {
                it.fromPort == 22 && it.toPort == 22 && it.protocol == "TCP"
            }
        }

        @Test
        fun `should be idempotent when adding same rule twice`() {
            val vpcId = vpcService.createVpc("test-sg-ingress-idem", "10.0.0.0/16", emptyMap())
            val sgId = securityGroupService.findOrCreateSecurityGroup(vpcId, "ingress-idem-sg", "Test", emptyMap())

            securityGroupService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")
            // Second call should not throw
            securityGroupService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")

            val details = securityGroupService.describeSecurityGroup(sgId)
            val matchingRules =
                details.inboundRules.filter {
                    it.fromPort == 9042 && it.toPort == 9042
                }
            assertThat(matchingRules).hasSize(1)
        }

        @Test
        fun `should add multiple different rules`() {
            val vpcId = vpcService.createVpc("test-sg-multi-rules", "10.0.0.0/16", emptyMap())
            val sgId = securityGroupService.findOrCreateSecurityGroup(vpcId, "multi-rules-sg", "Test", emptyMap())

            securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "0.0.0.0/0", "tcp")
            securityGroupService.authorizeSecurityGroupIngress(sgId, 9042, 9042, "10.0.0.0/8", "tcp")

            val details = securityGroupService.describeSecurityGroup(sgId)
            assertThat(details.inboundRules).hasSize(2)
        }
    }
}
