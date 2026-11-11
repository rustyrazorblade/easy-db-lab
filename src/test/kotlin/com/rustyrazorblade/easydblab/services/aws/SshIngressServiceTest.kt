package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import com.rustyrazorblade.easydblab.providers.aws.IngressAuthorization
import com.rustyrazorblade.easydblab.providers.aws.IngressRevocation
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupNotFoundException
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.ExternalIpService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import java.io.File
import java.io.IOException

class SshIngressServiceTest : BaseKoinTest() {
    private val vpcService: VpcService = mock()
    private var detectIp: () -> String = { "203.0.113.10" }
    private var detectionCalls = 0
    private val externalIpService =
        object : ExternalIpService {
            override fun getExternalIpAddress(): String {
                detectionCalls++
                return detectIp()
            }
        }
    private val stateManager by lazy { ClusterStateManager(File(tempDir, "state.json")) }
    private val service by lazy { DefaultSshIngressService(stateManager, externalIpService, vpcService) }

    @Test
    fun `an invalid value among good ones is reported with every bad value and no AWS call`() {
        givenState()

        val result = service.allow(listOf("203.0.113.7", "300.1.1.1", "foo"))

        assertThat(result).isInstanceOf(IngressResult.InvalidCidrs::class.java)
        val invalid = (result as IngressResult.InvalidCidrs).invalid
        assertThat(invalid.map { it.value }).containsExactly("300.1.1.1", "foo")
        assertThat(invalid).allSatisfy { assertThat(it.reason).isNotBlank() }
        verifyNoInteractions(vpcService)
        assertThat(detectionCalls).isZero()
    }

    @Test
    fun `a workspace without cluster state makes no AWS call`() {
        assertThat(service.allow(listOf("203.0.113.7"))).isEqualTo(IngressResult.NotInWorkspace)
        assertThat(service.revoke(emptyList())).isEqualTo(IngressResult.NotInWorkspace)
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `cluster state with no infrastructure makes no AWS call`() {
        stateManager.save(ClusterState(name = "test-cluster", versions = mutableMapOf()))

        assertThat(service.revoke(listOf("203.0.113.7"))).isEqualTo(IngressResult.NoSecurityGroupInState)
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `cluster state with no security group makes no AWS call`() {
        givenState(securityGroupId = null)

        assertThat(service.allow(listOf("203.0.113.7"))).isEqualTo(IngressResult.NoSecurityGroupInState)
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `detection that throws is a detection failure with no AWS call`() {
        givenState()
        detectIp = { throw IOException("connect timed out") }

        val result = service.allow(emptyList())

        assertThat(result).isInstanceOf(IngressResult.DetectionFailed::class.java)
        assertThat((result as IngressResult.DetectionFailed).reason).contains("connect timed out")
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `detection that returns something other than an IPv4 address is a detection failure`() {
        givenState()
        detectIp = { "<html>rate limited</html>" }

        val result = service.revoke(emptyList())

        assertThat(result).isInstanceOf(IngressResult.DetectionFailed::class.java)
        assertThat((result as IngressResult.DetectionFailed).reason).contains("<html>rate limited</html>")
        verifyNoInteractions(vpcService)
    }

    @Test
    fun `no value allows the detected public IP as a slash 32`() {
        givenState()
        detectIp = { "203.0.113.10\n" }
        whenever(vpcService.authorizeSecurityGroupIngress(any(), any(), any(), any(), any())).thenReturn(IngressAuthorization.ADDED)

        val result = service.allow(emptyList())

        assertThat(result).isEqualTo(
            IngressResult.Applied(SECURITY_GROUP, detected = true, outcomes = listOf(AllowOutcome.Added(cidr("203.0.113.10/32")))),
        )
        verify(vpcService).authorizeSecurityGroupIngress(SECURITY_GROUP, 22, 22, "203.0.113.10/32", "tcp")
    }

    @Test
    fun `a bare IP reaches AWS as a slash 32 and a CIDR exactly`() {
        givenState()
        whenever(vpcService.authorizeSecurityGroupIngress(any(), any(), any(), any(), any()))
            .thenReturn(IngressAuthorization.ADDED)
            .thenReturn(IngressAuthorization.ALREADY_PRESENT)

        val result = service.allow(listOf("203.0.113.7", "198.51.100.0/24"))

        assertThat(result).isEqualTo(
            IngressResult.Applied(
                SECURITY_GROUP,
                detected = false,
                outcomes =
                    listOf(
                        AllowOutcome.Added(cidr("203.0.113.7/32")),
                        AllowOutcome.AlreadyPresent(cidr("198.51.100.0/24")),
                    ),
            ),
        )
        verify(vpcService).authorizeSecurityGroupIngress(SECURITY_GROUP, 22, 22, "203.0.113.7/32", "tcp")
        verify(vpcService).authorizeSecurityGroupIngress(SECURITY_GROUP, 22, 22, "198.51.100.0/24", "tcp")
        assertThat(detectionCalls).isZero()
    }

    @Test
    fun `a mixed revoke reports the removed and the missing rule`() {
        givenState()
        whenever(vpcService.revokeSecurityGroupIngress(any(), any(), any(), eq("192.0.2.1/32"), any()))
            .thenReturn(IngressRevocation.NOT_FOUND)
        whenever(vpcService.revokeSecurityGroupIngress(any(), any(), any(), eq("203.0.113.7/32"), any()))
            .thenReturn(IngressRevocation.REMOVED)

        val result = service.revoke(listOf("192.0.2.1", "203.0.113.7"))

        assertThat((result as IngressResult.Applied).outcomes).containsExactly(
            RevokeOutcome.NotFound(cidr("192.0.2.1/32")),
            RevokeOutcome.Removed(cidr("203.0.113.7/32")),
        )
    }

    @Test
    fun `an AWS error on one CIDR does not stop the others`() {
        givenState()
        whenever(vpcService.authorizeSecurityGroupIngress(any(), any(), any(), eq("203.0.113.7/32"), any()))
            .thenThrow(
                ec2Exception("RulesPerSecurityGroupLimitExceeded", "The maximum number of rules per security group has been reached."),
            )
        whenever(vpcService.authorizeSecurityGroupIngress(any(), any(), any(), eq("198.51.100.0/24"), any()))
            .thenReturn(IngressAuthorization.ADDED)

        val result = service.allow(listOf("203.0.113.7", "198.51.100.0/24"))

        val outcomes = (result as IngressResult.Applied).outcomes
        assertThat(outcomes).hasSize(2)
        val failed = outcomes[0] as AllowOutcome.Failed
        assertThat(failed.cidr).isEqualTo(cidr("203.0.113.7/32"))
        assertThat(failed.reason).contains("The maximum number of rules per security group has been reached.")
        assertThat(outcomes[1]).isEqualTo(AllowOutcome.Added(cidr("198.51.100.0/24")))
    }

    @Test
    fun `an AWS error on a revoke is a failed outcome and the loop goes on`() {
        givenState()
        whenever(vpcService.revokeSecurityGroupIngress(any(), any(), any(), eq("203.0.113.7/32"), any()))
            .thenThrow(ec2Exception("UnauthorizedOperation", "You are not authorized to perform this operation."))
        whenever(vpcService.revokeSecurityGroupIngress(any(), any(), any(), eq("0.0.0.0/0"), any()))
            .thenReturn(IngressRevocation.REMOVED)

        val outcomes = (service.revoke(listOf("203.0.113.7", "0.0.0.0/0")) as IngressResult.Applied).outcomes

        assertThat(outcomes[0]).isInstanceOf(RevokeOutcome.Failed::class.java)
        assertThat(outcomes[1]).isEqualTo(RevokeOutcome.Removed(cidr("0.0.0.0/0")))
    }

    @Test
    fun `values that are the same CIDR after normalizing collapse into one change`() {
        givenState()
        whenever(vpcService.authorizeSecurityGroupIngress(any(), any(), any(), any(), any())).thenReturn(IngressAuthorization.ADDED)

        val result = service.allow(listOf("203.0.113.7", "203.0.113.7/32"))

        assertThat((result as IngressResult.Applied).outcomes).containsExactly(AllowOutcome.Added(cidr("203.0.113.7/32")))
        verify(vpcService, times(1)).authorizeSecurityGroupIngress(any(), any(), any(), any(), any())
    }

    @Test
    fun `a security group AWS no longer has fails the whole command and leaves the state alone`() {
        givenState()
        val stateBefore = File(tempDir, "state.json").readText()
        whenever(vpcService.revokeSecurityGroupIngress(any(), any(), any(), any(), any()))
            .thenThrow(SecurityGroupNotFoundException(SECURITY_GROUP))

        val result = service.revoke(listOf("203.0.113.7", "198.51.100.0/24"))

        assertThat(result).isEqualTo(IngressResult.SecurityGroupGone(SECURITY_GROUP))
        verify(vpcService, times(1)).revokeSecurityGroupIngress(any(), any(), any(), any(), any())
        assertThat(File(tempDir, "state.json").readText()).isEqualTo(stateBefore)
    }

    private fun givenState(securityGroupId: String? = SECURITY_GROUP) {
        stateManager.save(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                infrastructure = InfrastructureState(vpcId = "vpc-1", region = "us-west-2", securityGroupId = securityGroupId),
            ),
        )
    }

    private fun cidr(value: String): Ipv4Cidr = Ipv4Cidr.parse(value).getOrThrow()

    private fun ec2Exception(
        errorCode: String,
        message: String,
    ): Ec2Exception =
        Ec2Exception
            .builder()
            .message(message)
            .awsErrorDetails(
                AwsErrorDetails
                    .builder()
                    .errorCode(errorCode)
                    .errorMessage(message)
                    .build(),
            ).build() as Ec2Exception

    private companion object {
        const val SECURITY_GROUP = "sg-0123456789abcdef0"
    }
}
