package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.StressJobService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class StressListTest : BaseKoinTest() {
    private lateinit var mockStressJobService: StressJobService
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
            hosts = mapOf(ServerType.Control to listOf(testControlHost)),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<StressJobService> { mockStressJobService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockStressJobService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `execute fails when no control nodes`() {
        val stateNoControl =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = emptyMap(),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateNoControl)

        val command = StressList()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute runs list command with no args`() {
        whenever(mockStressJobService.runCommand(any(), any(), any()))
            .thenReturn(Result.success("Available workloads:\n  KeyValue\n  BasicTimeSeries"))

        val command = StressList()
        command.execute()

        verify(mockStressJobService).runCommand(
            eq(testControlHost),
            any(),
            argThat { this == listOf("list") },
        )

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Available workloads")
    }

    @Test
    fun `execute passes additional args`() {
        whenever(mockStressJobService.runCommand(any(), any(), any()))
            .thenReturn(Result.success("workload list"))

        val command = StressList()
        command.stressArgs = listOf("--verbose")
        command.execute()

        verify(mockStressJobService).runCommand(
            any(),
            any(),
            argThat { this == listOf("list", "--verbose") },
        )
    }

    @Test
    fun `execute fails when runCommand fails`() {
        whenever(mockStressJobService.runCommand(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s error")))

        val command = StressList()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to run command")
    }

    @Test
    fun `execute shows running message`() {
        whenever(mockStressJobService.runCommand(any(), any(), any()))
            .thenReturn(Result.success("output"))

        val command = StressList()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Running cassandra-easy-stress list")
    }
}
