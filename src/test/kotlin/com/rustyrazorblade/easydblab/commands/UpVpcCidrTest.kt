package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.VpcNetworkingConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import java.time.Duration
import kotlin.random.Random

/**
 * Tests how [Up] chooses the VPC CIDR: a random unused `10.X.0.0/16` when `init` left it unset,
 * retried with a different random block when creating the VPC fails. An explicit `--cidr` is used
 * as-is and never retried.
 */
class UpVpcCidrTest : UpTestFixture() {
    private val usedCidrs = listOf("10.0.0.0/16", "10.1.0.0/16")
    private val emitted = mutableListOf<Event>()

    @BeforeEach
    fun captureEvents() {
        whenever(mockVpcService.listAllVpcCidrs()).thenReturn(usedCidrs)
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun newUp(seed: Int): Up = Up(sshStartupDelay = Duration.ZERO, tailnetRetryInterval = Duration.ZERO, random = Random(seed))

    private fun vpcCreateFailure(): Ec2Exception = Ec2Exception.builder().message("VPC create failed").build() as Ec2Exception

    private fun attemptedCidrs(count: Int): List<String> {
        val cidrs = argumentCaptor<String>()
        verify(mockVpcService, times(count)).createVpc(any(), cidrs.capture(), any())
        return cidrs.allValues
    }

    @Test
    fun `an unset CIDR becomes a random unused block that is announced, persisted and used for networking`() {
        val chosen =
            (0 until 20)
                .map { seed ->
                    resetMocksForRun()
                    val state = happyState(cidr = null)
                    whenever(mockClusterStateManager.load()).thenReturn(state)

                    newUp(seed).execute()

                    val cidr = attemptedCidrs(1).single()
                    assertThat(emitted.filterIsInstance<Event.Setup.AutoSelectedCidr>().map { it.cidr }).containsExactly(cidr)
                    assertThat(state.initConfig?.cidr).isEqualTo(cidr)
                    val networking = argumentCaptor<VpcNetworkingConfig>()
                    verify(mockAwsInfrastructureService).setupVpcNetworking(networking.capture(), any())
                    assertThat(networking.firstValue.vpcCidr).isEqualTo(cidr)
                    cidr
                }.toSet()

        assertThat(chosen).doesNotContainAnyElementsOf(usedCidrs)
        // Random, not first-free: different seeds choose different blocks.
        assertThat(chosen).hasSizeGreaterThan(1)
    }

    private fun resetMocksForRun() {
        clearInvocations(mockVpcService, mockAwsInfrastructureService)
        emitted.clear()
    }

    @Test
    fun `a failed VPC creation retries with a different random unused block`() {
        val state = happyState(cidr = null)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockVpcService.createVpc(any(), any(), any()))
            .thenThrow(vpcCreateFailure())
            .thenReturn("vpc-123")

        newUp(seed = 7).execute()

        val (failed, succeeded) = attemptedCidrs(2)
        assertThat(succeeded).isNotEqualTo(failed)
        assertThat(listOf(failed, succeeded)).doesNotContainAnyElementsOf(usedCidrs)
        assertThat(state.initConfig?.cidr).isEqualTo(succeeded)
        assertThat(state.vpcId).isEqualTo("vpc-123")
    }

    @Test
    fun `up fails after the fixed number of attempts, each on a different block`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cidr = null))
        whenever(mockVpcService.createVpc(any(), any(), any())).thenThrow(vpcCreateFailure())

        assertThatThrownBy { newUp(seed = 7).execute() }.hasMessageContaining("VPC create failed")

        val attempted = attemptedCidrs(Constants.Vpc.CIDR_AUTO_SELECT_MAX_ATTEMPTS)
        assertThat(attempted).doesNotHaveDuplicates().doesNotContainAnyElementsOf(usedCidrs)
    }

    @Test
    fun `resuming with a recorded VPC but no recorded CIDR adopts the VPC's actual CIDR`() {
        val state = happyState(cidr = null).apply { vpcId = "vpc-existing" }
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockVpcService.getVpcName("vpc-existing")).thenReturn("test-cluster")
        whenever(mockVpcService.getVpcCidr("vpc-existing")).thenReturn("10.42.0.0/16")

        newUp(seed = 7).execute()

        assertThat(state.initConfig?.cidr).isEqualTo("10.42.0.0/16")
        val networking = argumentCaptor<VpcNetworkingConfig>()
        verify(mockAwsInfrastructureService).setupVpcNetworking(networking.capture(), any())
        assertThat(networking.firstValue.vpcId).isEqualTo("vpc-existing")
        assertThat(networking.firstValue.vpcCidr).isEqualTo("10.42.0.0/16")
        verify(mockVpcService, never()).createVpc(any(), any(), any())
        assertThat(emitted.filterIsInstance<Event.Setup.AutoSelectedCidr>()).isEmpty()
    }

    @Test
    fun `an explicit CIDR is used as-is and a failed creation is not retried`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cidr = "172.16.0.0/16"))
        whenever(mockVpcService.createVpc(any(), any(), any())).thenThrow(vpcCreateFailure())

        assertThatThrownBy { newUp(seed = 7).execute() }.hasMessageContaining("VPC create failed")

        verify(mockVpcService).createVpc(any(), eq("172.16.0.0/16"), any())
        assertThat(emitted.filterIsInstance<Event.Setup.AutoSelectedCidr>()).isEmpty()
    }
}
