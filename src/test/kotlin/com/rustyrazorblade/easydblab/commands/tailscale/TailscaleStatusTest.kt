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
import org.mockito.kotlin.whenever

class TailscaleStatusTest : BaseKoinTest() {
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

        val command = TailscaleStatus()
        command.execute()

        val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errorOutput).contains("No control node found")
    }

    @Test
    fun `execute shows not running when disconnected`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(false))

        val command = TailscaleStatus()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("not running")
    }

    @Test
    fun `execute shows status when connected`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(true))
        whenever(mockTailscaleService.getStatus(any()))
            .thenReturn(Result.success("IP: 100.64.0.1\nPeers: 2\nRoutes: 10.0.0.0/16"))

        val command = TailscaleStatus()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Tailscale status on control0")
        assertThat(output).contains("100.64.0.1")
    }

    @Test
    fun `execute handles status retrieval failure`() {
        whenever(mockTailscaleService.isConnected(any())).thenReturn(Result.success(true))
        whenever(mockTailscaleService.getStatus(any()))
            .thenReturn(Result.failure(RuntimeException("SSH timeout")))

        val command = TailscaleStatus()
        command.execute()

        val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errorOutput).contains("Failed to get Tailscale status")
    }
}
