package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
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

class PlatformInfoTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private lateinit var outputHandler: BufferedOutputHandler

    private fun host(
        alias: String,
        instanceId: String,
    ) = ClusterHost(
        publicIp = "54.0.0.1",
        privateIp = "10.0.0.1",
        alias = alias,
        availabilityZone = "us-west-2a",
        instanceId = instanceId,
    )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun captureOutputHandler() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    private fun stateWith(
        dbCount: Int,
        appCount: Int,
    ): ClusterState {
        val dbHosts = (0 until dbCount).map { host("db$it", "i-db$it") }
        val appHosts = (0 until appCount).map { host("app$it", "i-app$it") }
        val hosts =
            buildMap {
                if (dbHosts.isNotEmpty()) put(ServerType.Cassandra, dbHosts)
                if (appHosts.isNotEmpty()) put(ServerType.Stress, appHosts)
            }
        return ClusterState(name = "test-cluster", versions = mutableMapOf(), hosts = hosts)
    }

    @Test
    fun `outputs both storage classes`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(dbCount = 3, appCount = 2))

        PlatformInfo().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains(Constants.K8s.LOCAL_STORAGE_CLASS)
        assertThat(output).contains(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
    }

    @Test
    fun `outputs correct db and app node counts`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(dbCount = 3, appCount = 2))

        PlatformInfo().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("DB nodes: 3")
        assertThat(output).contains("App nodes: 2")
    }

    @Test
    fun `outputs node ordinal label key`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(dbCount = 1, appCount = 0))

        PlatformInfo().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains(Constants.NODE_ORDINAL_LABEL)
    }

    @Test
    fun `outputs example helm values snippet`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(dbCount = 2, appCount = 1))

        PlatformInfo().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("nodeSelector")
        assertThat(output).contains(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
    }

    @Test
    fun `reports zero app nodes when no stress hosts configured`() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Cassandra to listOf(host("db0", "i-db0"))),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        PlatformInfo().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("App nodes: 0")
        assertThat(output).contains("DB nodes: 1")
    }
}
