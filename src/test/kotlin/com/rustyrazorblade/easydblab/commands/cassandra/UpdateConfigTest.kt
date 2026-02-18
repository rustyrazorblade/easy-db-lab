package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class UpdateConfigTest : BaseKoinTest() {
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
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)

        // Create required patch file
        File("cassandra.patch.yaml").writeText(
            """
            cluster_name: test-cluster
            num_tokens: 4
            """.trimIndent(),
        )

        // Create required sidecar config
        File(Constants.ConfigPaths.CASSANDRA_SIDECAR_CONFIG).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(
                """
                cassandra_instances:
                  - host: 127.0.0.1
                driver_parameters:
                  contact_points:
                    - "127.0.0.1:9042"
                """.trimIndent(),
            )
        }
    }

    @AfterEach
    fun cleanup() {
        File("cassandra.patch.yaml").delete()
        File(Constants.ConfigPaths.CASSANDRA_SIDECAR_CONFIG).delete()
    }

    @Test
    fun `execute uploads config to cassandra hosts`() {
        val command = UpdateConfig()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Uploading cassandra.patch.yaml")
        assertThat(output).contains("Configuration updated")
    }

    @Test
    fun `execute uploads sidecar config`() {
        val command = UpdateConfig()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Sidecar configuration updated")
    }
}
