package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.SidecarService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RestartTest : BaseKoinTest() {
    private lateinit var mockCassandraService: CassandraService
    private lateinit var mockSidecarService: SidecarService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var hostOperationsService: HostOperationsService
    private lateinit var outputHandler: BufferedOutputHandler

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(testCassandraHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CassandraService> { mockCassandraService }
                single<SidecarService> { mockSidecarService }
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockCassandraService = mock()
        mockSidecarService = mock()
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockCassandraService.restart(any())).thenReturn(Result.success(Unit))
        whenever(mockSidecarService.restart(any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `execute restarts cassandra on all nodes`() {
        val command = Restart()
        command.execute()

        verify(mockCassandraService).restart(any())
    }

    @Test
    fun `execute restarts sidecar on all nodes`() {
        val command = Restart()
        command.execute()

        verify(mockSidecarService).restart(any())
    }

    @Test
    fun `execute outputs restart messages`() {
        val command = Restart()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Restarting cassandra service")
        assertThat(output).contains("cassandra-sidecar restart completed")
    }
}
