package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [ConfigureAxonOps]'s operator-facing progress output. */
class ConfigureAxonOpsTest : BaseKoinTest() {
    private val clusterStateManager = mock<ClusterStateManager>()
    private lateinit var outputHandler: BufferedOutputHandler

    private val clusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to
                        listOf(ClusterHost("54.1.2.3", "10.0.1.1", "db0", "us-west-2a", instanceId = "i-db0")),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { clusterStateManager }
                single { HostOperationsService(clusterStateManager) }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(clusterStateManager.load()).thenReturn(clusterState)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `progress output names each host by its alias`() {
        ConfigureAxonOps()
            .apply {
                org = "lab-org"
                key = "lab-key"
            }.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Configure axonops on db0")
        assertThat(output).doesNotContain("Host(", "i-db0", "54.1.2.3")
    }
}
