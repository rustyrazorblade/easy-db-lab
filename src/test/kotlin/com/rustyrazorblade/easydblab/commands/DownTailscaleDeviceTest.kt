package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * `down` removes this cluster's control node from the tailnet, by the device ID `tailscale start`
 * recorded, and fails loudly when it cannot rather than leaving the device behind silently.
 *
 * The Tailscale API and AWS teardown are mocked (external services with side effects); cluster
 * state is real, persisted to a temp file.
 */
class DownTailscaleDeviceTest : BaseKoinTest() {
    private lateinit var stateManager: ClusterStateManager
    private lateinit var teardownService: AwsInfrastructureService
    private lateinit var tailscaleService: TailscaleService
    private lateinit var outputHandler: BufferedOutputHandler

    private val resources = DiscoveredResources(vpcId = "vpc-123", vpcName = "test-cluster")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(tempDir, "state.json")) }
                single { mock<AwsInfrastructureService>() }
                single { mock<TailscaleService>() }
                single { mock<AwsS3BucketService>() }
            },
        )

    @BeforeEach
    fun setup() {
        stateManager = getKoin().get()
        teardownService = getKoin().get()
        tailscaleService = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(teardownService.teardownVpc(eq("vpc-123"), any())).thenReturn(TeardownResult.success(resources))
    }

    private fun saveState(deviceId: String?) =
        stateManager.save(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), vpcId = "vpc-123")
                .apply { tailscaleDeviceId = deviceId },
        )

    private fun withTailscaleCredentials() {
        getKoin().declare(
            getKoin().get<User>().copy(tailscaleClientId = "client-id", tailscaleClientSecret = "client-secret"),
        )
    }

    private fun runDown(): Int =
        Down()
            .apply {
                autoApprove = true
                force = true
            }.call()

    private fun output(): String = (outputHandler.messages + outputHandler.errors.map { it.first }).joinToString("\n")

    @Test
    fun `down deletes the recorded tailnet device and forgets it`() {
        saveState("nControl0CNTRL")
        withTailscaleCredentials()

        val exitCode = runDown()

        verify(tailscaleService).deleteDevice("client-id", "client-secret", "nControl0CNTRL")
        assertThat(exitCode).isEqualTo(0)
        assertThat(stateManager.load().tailscaleDeviceId).isNull()
    }

    @Test
    fun `down with no recorded device deletes nothing`() {
        saveState(null)
        withTailscaleCredentials()

        val exitCode = runDown()

        verify(tailscaleService, never()).deleteDevice(any(), any(), any())
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `a device delete the credentials may not perform fails down and keeps the device for a retry`() {
        saveState("nControl0CNTRL")
        withTailscaleCredentials()
        whenever(tailscaleService.deleteDevice(any(), any(), any()))
            .thenThrow(TailscaleApiException("not allowed to delete device nControl0CNTRL; grant devices:core"))

        val exitCode = runDown()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(output()).contains("nControl0CNTRL").contains("devices:core")
        assertThat(stateManager.load().tailscaleDeviceId).isEqualTo("nControl0CNTRL")
    }

    @Test
    fun `the next down retries a device delete that failed, after the cluster state was cleared`() {
        saveState("nControl0CNTRL")
        withTailscaleCredentials()
        doThrow(TailscaleApiException("tailnet unreachable"))
            .doNothing()
            .whenever(tailscaleService)
            .deleteDevice(any(), any(), any())

        val firstExit = runDown()
        val secondExit = runDown()

        assertThat(firstExit).isEqualTo(Constants.ExitCodes.ERROR)
        verify(tailscaleService, times(2)).deleteDevice("client-id", "client-secret", "nControl0CNTRL")
        assertThat(secondExit).isEqualTo(0)
        assertThat(stateManager.load().tailscaleDeviceId).isNull()
    }

    @Test
    fun `a successful device delete leaves the cluster state cleared`() {
        saveState("nControl0CNTRL")
        withTailscaleCredentials()

        runDown()

        val state = stateManager.load()
        assertThat(state.vpcId).isNull()
        assertThat(state.tailscaleDeviceId).isNull()
        assertThat(state.isInfrastructureUp()).isFalse()
    }

    @Test
    fun `a recorded device with no Tailscale credentials fails down instead of skipping it`() {
        saveState("nControl0CNTRL")

        val exitCode = runDown()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(output()).contains("nControl0CNTRL").contains("credentials")
        verify(tailscaleService, never()).deleteDevice(any(), any(), any())
    }
}
