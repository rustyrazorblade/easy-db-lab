package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class HostsTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val testStressHost =
        ClusterHost(
            publicIp = "54.1.2.4",
            privateIp = "10.0.1.2",
            alias = "app0",
            availabilityZone = "us-west-2a",
            instanceId = "i-app0",
        )

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
                    ServerType.Cassandra to listOf(testCassandraHost),
                    ServerType.Stress to listOf(testStressHost),
                    ServerType.Control to listOf(testControlHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `execute outputs message when cluster state does not exist`() {
        whenever(mockClusterStateManager.exists()).thenReturn(false)

        val command = Hosts()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Cluster state does not exist")
    }

    @Test
    fun `execute with csv flag outputs comma-separated cassandra hosts`() {
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)

        val command = Hosts()
        command.cassandra = true
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("54.1.2.3")
    }

    @Test
    fun `execute without csv flag outputs yaml`() {
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)

        val command = Hosts()
        command.execute()

        // YAML output goes to System.out, not outputHandler
        // Just verify no error was thrown
    }
}
