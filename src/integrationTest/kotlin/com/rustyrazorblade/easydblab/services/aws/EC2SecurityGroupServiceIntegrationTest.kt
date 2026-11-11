package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.IngressAuthorization
import com.rustyrazorblade.easydblab.providers.aws.IngressRevocation
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupNotFoundException
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        fun `reports ADDED for a new rule and ALREADY_PRESENT for the same rule again`() {
            val sgId = newSecurityGroup("authorize-result")

            val first = securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "203.0.113.7/32", "tcp")
            val second = securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "203.0.113.7/32", "tcp")

            assertThat(first).isEqualTo(IngressAuthorization.ADDED)
            assertThat(second).isEqualTo(IngressAuthorization.ALREADY_PRESENT)
        }

        @Test
        fun `throws SecurityGroupNotFoundException for a group that does not exist`() {
            assertThatThrownBy {
                securityGroupService.authorizeSecurityGroupIngress(MISSING_GROUP, 22, 22, "203.0.113.7/32", "tcp")
            }.isInstanceOfSatisfying(SecurityGroupNotFoundException::class.java) {
                assertThat(it.securityGroupId).isEqualTo(MISSING_GROUP)
            }
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

    @Nested
    inner class RevokeSecurityGroupIngress {
        @Test
        fun `removes only its own CIDR's SSH rule and leaves every other rule and egress`() {
            val vpcCidr = "10.0.0.0/16"
            val sgId = newSecurityGroup("revoke-exact", vpcCidr)
            securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "203.0.113.7/32", "tcp")
            securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "198.51.100.0/24", "tcp")
            securityGroupService.authorizeSecurityGroupIngress(sgId, 0, 65535, vpcCidr, "tcp")
            securityGroupService.authorizeSecurityGroupIngress(sgId, 0, 65535, vpcCidr, "udp")
            securityGroupService.authorizeSecurityGroupIngress(sgId, -1, -1, vpcCidr, "icmp")
            val egressBefore = securityGroupService.describeSecurityGroup(sgId).outboundRules

            val result = securityGroupService.revokeSecurityGroupIngress(sgId, 22, 22, "203.0.113.7/32", "tcp")

            assertThat(result).isEqualTo(IngressRevocation.REMOVED)
            val after = securityGroupService.describeSecurityGroup(sgId)
            assertThat(after.inboundRules.ruleKeys()).containsExactlyInAnyOrder(
                RuleKey("TCP", 22, 22, "198.51.100.0/24"),
                RuleKey("TCP", 0, 65535, vpcCidr),
                RuleKey("UDP", 0, 65535, vpcCidr),
                RuleKey("ICMP", -1, -1, vpcCidr),
            )
            assertThat(after.outboundRules).isEqualTo(egressBefore)
            assertThat(egressBefore).isNotEmpty()
        }

        @Test
        fun `reports NOT_FOUND and changes nothing when the CIDR has no SSH rule`() {
            val sgId = newSecurityGroup("revoke-missing")
            securityGroupService.authorizeSecurityGroupIngress(sgId, 22, 22, "203.0.113.7/32", "tcp")
            val before = securityGroupService.describeSecurityGroup(sgId)

            val result = securityGroupService.revokeSecurityGroupIngress(sgId, 22, 22, "192.0.2.1/32", "tcp")

            assertThat(result).isEqualTo(IngressRevocation.NOT_FOUND)
            assertThat(securityGroupService.describeSecurityGroup(sgId)).isEqualTo(before)
        }

        @Test
        fun `throws SecurityGroupNotFoundException for a group that does not exist`() {
            assertThatThrownBy {
                securityGroupService.revokeSecurityGroupIngress(MISSING_GROUP, 22, 22, "203.0.113.7/32", "tcp")
            }.isInstanceOfSatisfying(SecurityGroupNotFoundException::class.java) {
                assertThat(it.securityGroupId).isEqualTo(MISSING_GROUP)
            }
        }
    }

    /** One CIDR of one rule, so a rule that lists several CIDRs compares as several keys. */
    private data class RuleKey(
        val protocol: String,
        val fromPort: Int?,
        val toPort: Int?,
        val cidr: String,
    )

    private fun List<SecurityGroupRuleInfo>.ruleKeys(): List<RuleKey> =
        flatMap { rule -> rule.cidrBlocks.map { RuleKey(rule.protocol, rule.fromPort, rule.toPort, it) } }

    private fun newSecurityGroup(
        name: String,
        vpcCidr: String = "10.0.0.0/16",
    ): String {
        val vpcId = vpcService.createVpc("test-sg-$name", vpcCidr, emptyMap())
        return securityGroupService.findOrCreateSecurityGroup(vpcId, "$name-sg", "Test", emptyMap())
    }

    private companion object {
        /** Well formed, so EC2 answers InvalidGroup.NotFound rather than a malformed-ID error. */
        const val MISSING_GROUP = "sg-0123456789abcdef0"
    }
}
