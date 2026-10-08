package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for [DownloadConfig]: it downloads a version's config into the workspace once, and says
 * so when it skips because the directory is already there.
 */
class DownloadConfigTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private val remoteOps = mock<RemoteOperationsService>()
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
                factory<RemoteOperationsService> { remoteOps }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(remoteOps.getRemoteVersion(any(), eq("current"))).thenReturn(Version("/usr/local/cassandra/5.0"))
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `downloads the version's config into the workspace when it is not there yet`() {
        DownloadConfig().execute()

        val localDir = File(context.workingDirectory, "5.0")
        assertThat(localDir).isDirectory()
        verify(remoteOps).downloadDirectory(any(), eq("/usr/local/cassandra/5.0/conf"), eq(localDir), any(), any())
    }

    /** An existing directory is never overwritten, and the operator is told why nothing changed. */
    @Test
    fun `an existing config directory is reported and nothing is downloaded`() {
        File(context.workingDirectory, "5.0").mkdirs()

        DownloadConfig().execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("5.0 already exists", "skipping the download")
        verify(remoteOps, never()).downloadDirectory(any(), any(), any(), any(), any())
    }
}
