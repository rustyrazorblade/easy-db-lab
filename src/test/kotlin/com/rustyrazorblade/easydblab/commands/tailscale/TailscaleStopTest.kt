package com.rustyrazorblade.easydblab.commands.tailscale

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.TailscaleService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TailscaleStopTest : BaseKoinTest() {
    private lateinit var mockTailscaleService: TailscaleService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testControlHost =
        ClusterHost(
            publicIp = "54.1.2.5",
            privateIp = "10.0.1.3",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<TailscaleService> { mockTailscaleService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockTailscaleService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `execute shows error when no control node`() {
        val stateNoControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = emptyMap(),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateNoControl)

        val command = TailscaleStop()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("No control node found")
    }

    @Test
    fun `execute shows not running when already stopped`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(false))

        val command = TailscaleStop()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("not running")
    }

    @Test
    fun `execute stops tailscale successfully`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(true))
        whenever(mockTailscaleService.stopTailscale(any())).thenReturn(Result.success(Unit))

        val command = TailscaleStop()
        command.execute()

        verify(mockTailscaleService).stopTailscale(any())
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("stopped successfully")
    }

    @Test
    fun `execute handles stop failure`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(true))
        whenever(mockTailscaleService.stopTailscale(any()))
            .thenReturn(Result.failure(RuntimeException("Permission denied")))

        val command = TailscaleStop()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Failed to stop Tailscale")
        assertThat(output).contains("Permission denied")
    }
}
