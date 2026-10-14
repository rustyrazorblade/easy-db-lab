package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.IpPermission
import software.amazon.awssdk.services.ec2.model.IpRange
import software.amazon.awssdk.services.ec2.model.RevokeSecurityGroupIngressRequest
import software.amazon.awssdk.services.ec2.model.SecurityGroup

/**
 * Unit tests for [EC2SecurityGroupService] behavior LocalStack cannot reproduce: AWS error codes
 * raced against a describe, and the mapping of described rules into [SecurityGroupDetails].
 *
 * For the find-or-create and rule round trips, see `EC2SecurityGroupServiceIntegrationTest`.
 */
internal class EC2SecurityGroupServiceTest {
    private val mockEc2Client: Ec2Client = mock()
    private val eventBus = EventBus()
    private val vpcService = EC2SecurityGroupService(mockEc2Client, eventBus)

    @Test
    fun `authorizeSecurityGroupIngress should handle duplicate permission exception`() {
        givenSecurityGroup()
        whenever(mockEc2Client.authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())).thenThrow(
            ec2Exception("InvalidPermission.Duplicate"),
        )

        // Should not throw exception - should handle duplicate gracefully
        vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")

        verify(mockEc2Client).authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())
    }

    @Test
    fun `authorizeSecurityGroupIngress retries a describe that fails with a server error`() {
        val securityGroup = SecurityGroup.builder().groupId("sg-12345").build()
        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>()))
            .thenThrow(Ec2Exception.builder().statusCode(503).build())
            .thenReturn(DescribeSecurityGroupsResponse.builder().securityGroups(securityGroup).build())

        vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "203.0.113.7/32", "tcp")

        verify(mockEc2Client).authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())
    }

    @Test
    fun `revokeSecurityGroupRules retries a describe that fails with a server error`() {
        val sshRule =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(22)
                .toPort(22)
                .ipRanges(IpRange.builder().cidrIp("203.0.113.7/32").build())
                .build()
        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .ipPermissions(sshRule)
                .build()
        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>()))
            .thenThrow(Ec2Exception.builder().statusCode(503).build())
            .thenReturn(DescribeSecurityGroupsResponse.builder().securityGroups(securityGroup).build())

        vpcService.revokeSecurityGroupRules("sg-12345")

        val request = argumentCaptor<RevokeSecurityGroupIngressRequest>()
        verify(mockEc2Client).revokeSecurityGroupIngress(request.capture())
        assertThat(request.firstValue.ipPermissions()).containsExactly(sshRule)
    }

    @Test
    fun `authorizeSecurityGroupIngress should throw on non-duplicate exception`() {
        givenSecurityGroup()
        whenever(mockEc2Client.authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())).thenThrow(
            ec2Exception("InvalidGroup.NotFound"),
        )

        assertThrows<Ec2Exception> {
            vpcService.authorizeSecurityGroupIngress("sg-12345", 22, 22, "0.0.0.0/0", "tcp")
        }
    }

    @Test
    fun `authorizeSecurityGroupIngress skips an ICMP rule that already exists`() {
        val existingIcmpRule =
            IpPermission
                .builder()
                .ipProtocol("icmp")
                .fromPort(-1)
                .toPort(-1)
                .ipRanges(IpRange.builder().cidrIp("10.0.0.0/16").build())
                .build()
        givenSecurityGroup(existingIcmpRule)

        vpcService.authorizeSecurityGroupIngress("sg-12345", -1, -1, "10.0.0.0/16", "icmp")

        verify(mockEc2Client, never()).authorizeSecurityGroupIngress(any<AuthorizeSecurityGroupIngressRequest>())
    }

    @Test
    fun `authorizeSecurityGroupIngress builds an all-types ICMP permission`() {
        givenSecurityGroup()

        vpcService.authorizeSecurityGroupIngress("sg-12345", -1, -1, "10.0.0.0/16", "icmp")

        val request = argumentCaptor<AuthorizeSecurityGroupIngressRequest>()
        verify(mockEc2Client).authorizeSecurityGroupIngress(request.capture())
        val permission = request.firstValue.ipPermissions().single()
        assertThat(permission.ipProtocol()).isEqualTo("icmp")
        assertThat(permission.fromPort()).isEqualTo(-1)
        assertThat(permission.toPort()).isEqualTo(-1)
        assertThat(permission.ipRanges().map { it.cidrIp() }).containsExactly("10.0.0.0/16")
    }

    @Test
    fun `authorizeSecurityGroupIngress reports an ICMP rule as all ICMP types`() {
        assertThat(configuredRuleText(-1, -1, "icmp"))
            .isEqualTo("Configured security group ingress rule for all ICMP types")
    }

    @Test
    fun `authorizeSecurityGroupIngress reports a TCP range as a port range`() {
        assertThat(configuredRuleText(9000, 9100, "tcp"))
            .isEqualTo("Configured security group ingress rule for ports 9000-9100")
    }

    @Test
    fun `authorizeSecurityGroupIngress reports a single TCP port`() {
        assertThat(configuredRuleText(22, 22, "tcp"))
            .isEqualTo("Configured security group ingress rule for port 22")
    }

    private fun configuredRuleText(
        fromPort: Int,
        toPort: Int,
        protocol: String,
    ): String {
        givenSecurityGroup()
        val events = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )

        vpcService.authorizeSecurityGroupIngress("sg-12345", fromPort, toPort, "10.0.0.0/16", protocol)

        return events
            .filterIsInstance<Event.Infra.SecurityGroupRuleConfigured>()
            .single()
            .toDisplayString()
    }

    private fun givenSecurityGroup(vararg permissions: IpPermission) {
        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-12345")
                .ipPermissions(permissions.toList())
                .build()
        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(
            DescribeSecurityGroupsResponse.builder().securityGroups(securityGroup).build(),
        )
    }

    private fun ec2Exception(errorCode: String): Ec2Exception =
        Ec2Exception
            .builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
            .build() as Ec2Exception

    @Test
    fun `describeSecurityGroup throws when security group not found`() {
        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(emptyList())
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        assertThatThrownBy {
            vpcService.describeSecurityGroup("sg-nonexistent")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("sg-nonexistent")
    }

    @Test
    fun `describeSecurityGroup returns details with inbound and outbound rules`() {
        val inboundPermission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(22)
                .toPort(22)
                .ipRanges(
                    IpRange
                        .builder()
                        .cidrIp("10.0.0.0/8")
                        .description("SSH access")
                        .build(),
                ).build()

        val outboundPermission =
            IpPermission
                .builder()
                .ipProtocol("-1")
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
                .groupName("test-security-group")
                .description("Test security group for cluster")
                .vpcId("vpc-abc123")
                .ipPermissions(inboundPermission)
                .ipPermissionsEgress(outboundPermission)
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-12345")

        assertThat(result).isNotNull
        assertThat(result.securityGroupId).isEqualTo("sg-12345")
        assertThat(result.name).isEqualTo("test-security-group")
        assertThat(result.description).isEqualTo("Test security group for cluster")
        assertThat(result.vpcId).isEqualTo("vpc-abc123")

        // Verify inbound rules
        assertThat(result.inboundRules).hasSize(1)
        assertThat(result.inboundRules[0].protocol).isEqualTo("TCP")
        assertThat(result.inboundRules[0].fromPort).isEqualTo(22)
        assertThat(result.inboundRules[0].toPort).isEqualTo(22)
        assertThat(result.inboundRules[0].cidrBlocks).containsExactly("10.0.0.0/8")
        assertThat(result.inboundRules[0].description).isEqualTo("SSH access")

        // Verify outbound rules
        assertThat(result.outboundRules).hasSize(1)
        assertThat(result.outboundRules[0].protocol).isEqualTo("All")
        assertThat(result.outboundRules[0].fromPort).isNull()
        assertThat(result.outboundRules[0].toPort).isNull()
        assertThat(result.outboundRules[0].cidrBlocks).containsExactly("0.0.0.0/0")
    }

    @Test
    fun `describeSecurityGroup handles multiple CIDR blocks`() {
        val permission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(9042)
                .toPort(9042)
                .ipRanges(
                    IpRange.builder().cidrIp("10.0.0.0/8").build(),
                    IpRange.builder().cidrIp("192.168.0.0/16").build(),
                    IpRange.builder().cidrIp("172.16.0.0/12").build(),
                ).build()

        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-multi")
                .groupName("multi-cidr-sg")
                .description("Security group with multiple CIDRs")
                .vpcId("vpc-xyz")
                .ipPermissions(permission)
                .ipPermissionsEgress(emptyList())
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-multi")

        assertThat(result).isNotNull
        assertThat(result.inboundRules).hasSize(1)
        assertThat(result.inboundRules[0].cidrBlocks)
            .containsExactly("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12")
    }

    @Test
    fun `describeSecurityGroup provides default descriptions for well-known ports`() {
        val sshPermission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(22)
                .toPort(22)
                .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                .build()

        val cqlPermission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(9042)
                .toPort(9042)
                .ipRanges(IpRange.builder().cidrIp("10.0.0.0/8").build())
                .build()

        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-known-ports")
                .groupName("known-ports-sg")
                .description("SG with well-known ports")
                .vpcId("vpc-xyz")
                .ipPermissions(sshPermission, cqlPermission)
                .ipPermissionsEgress(emptyList())
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-known-ports")

        assertThat(result).isNotNull
        assertThat(result.inboundRules).hasSize(2)

        val sshRule = result.inboundRules.find { it.fromPort == 22 }
        assertThat(sshRule).isNotNull
        assertThat(sshRule!!.description).isEqualTo("SSH")

        val cqlRule = result.inboundRules.find { it.fromPort == 9042 }
        assertThat(cqlRule).isNotNull
        assertThat(cqlRule!!.description).isEqualTo("Cassandra CQL")
    }

    @Test
    fun `describeSecurityGroup handles port ranges`() {
        val rangePermission =
            IpPermission
                .builder()
                .ipProtocol("tcp")
                .fromPort(7000)
                .toPort(7001)
                .ipRanges(IpRange.builder().cidrIp("10.0.0.0/8").build())
                .build()

        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-range")
                .groupName("port-range-sg")
                .description("SG with port range")
                .vpcId("vpc-xyz")
                .ipPermissions(rangePermission)
                .ipPermissionsEgress(emptyList())
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-range")

        assertThat(result).isNotNull
        assertThat(result.inboundRules).hasSize(1)
        assertThat(result.inboundRules[0].fromPort).isEqualTo(7000)
        assertThat(result.inboundRules[0].toPort).isEqualTo(7001)
        // Port range should not have a default description
        assertThat(result.inboundRules[0].description).isNull()
    }

    @Test
    fun `describeSecurityGroup handles all traffic rule`() {
        val allTrafficPermission =
            IpPermission
                .builder()
                .ipProtocol("-1")
                .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                .build()

        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-all")
                .groupName("all-traffic-sg")
                .description("SG allowing all traffic")
                .vpcId("vpc-xyz")
                .ipPermissions(allTrafficPermission)
                .ipPermissionsEgress(emptyList())
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-all")

        assertThat(result).isNotNull
        assertThat(result.inboundRules).hasSize(1)
        assertThat(result.inboundRules[0].protocol).isEqualTo("All")
        assertThat(result.inboundRules[0].fromPort).isNull()
        assertThat(result.inboundRules[0].toPort).isNull()
        assertThat(result.inboundRules[0].description).isEqualTo("All traffic")
    }

    @Test
    fun `describeSecurityGroup handles empty rules`() {
        val securityGroup =
            SecurityGroup
                .builder()
                .groupId("sg-empty")
                .groupName("empty-rules-sg")
                .description("SG with no rules")
                .vpcId("vpc-xyz")
                .ipPermissions(emptyList())
                .ipPermissionsEgress(emptyList())
                .build()

        val response =
            DescribeSecurityGroupsResponse
                .builder()
                .securityGroups(securityGroup)
                .build()

        whenever(mockEc2Client.describeSecurityGroups(any<DescribeSecurityGroupsRequest>())).thenReturn(response)

        val result = vpcService.describeSecurityGroup("sg-empty")

        assertThat(result).isNotNull
        assertThat(result.inboundRules).isEmpty()
        assertThat(result.outboundRules).isEmpty()
    }
}
