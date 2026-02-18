package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RegionTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

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
    fun `execute outputs region from cluster state`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-east-1"),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = Region()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).isEqualTo("us-east-1")
    }

    @Test
    fun `execute errors when no region configured`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = null,
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = Region()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No region configured")
    }
}
