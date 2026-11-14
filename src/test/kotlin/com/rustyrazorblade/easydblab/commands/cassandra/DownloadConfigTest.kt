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
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
                single { HostOperationsService(mockClusterStateManager) }
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

    private val secondCassandraHost = testCassandraHost.copy(alias = "db1", privateIp = "10.0.1.2", instanceId = "i-db1")

    /** `--hosts` used to parse and be ignored: the download always came from the first host. */
    @Test
    fun `--hosts downloads from the targeted host, not the first host`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(hosts = mapOf(ServerType.Cassandra to listOf(testCassandraHost, secondCassandraHost))),
        )
        whenever(remoteOps.getRemoteVersion(any(), eq("current"))).thenReturn(Version("/usr/local/cassandra/5.0"))
        whenever(remoteOps.getRemoteVersion(eq(secondCassandraHost.toHost()), eq("current")))
            .thenReturn(Version("/usr/local/cassandra/5.0.9-custom"))

        DownloadConfig().apply { hosts.hostList = "db1" }.execute()

        val localDir = File(context.workingDirectory, "5.0.9-custom")
        verify(remoteOps, times(1)).downloadDirectory(any(), any(), any(), any(), any())
        verify(remoteOps).downloadDirectory(
            eq(secondCassandraHost.toHost()),
            eq("/usr/local/cassandra/5.0.9-custom/conf"),
            eq(localDir),
            any(),
            any(),
        )
    }

    @Test
    fun `--hosts naming no cassandra host fails instead of downloading from another one`() {
        assertThatThrownBy { DownloadConfig().apply { hosts.hostList = "db9" }.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db9")
        verify(remoteOps, never()).downloadDirectory(any(), any(), any(), any(), any())
    }

    /**
     * `cassandra use --hosts=db2 <build>` leaves db2 on a different version than db0. update-config
     * then looks for a directory per version, so download-config must produce one per version.
     */
    @Test
    fun `targeted hosts on different versions get a directory each`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(hosts = mapOf(ServerType.Cassandra to listOf(testCassandraHost, secondCassandraHost))),
        )
        whenever(remoteOps.getRemoteVersion(eq(testCassandraHost.toHost()), eq("current")))
            .thenReturn(Version("/usr/local/cassandra/5.0"))
        whenever(remoteOps.getRemoteVersion(eq(secondCassandraHost.toHost()), eq("current")))
            .thenReturn(Version("/usr/local/cassandra/5.0.9-custom"))

        DownloadConfig().execute()

        verify(remoteOps).downloadDirectory(
            eq(testCassandraHost.toHost()),
            eq("/usr/local/cassandra/5.0/conf"),
            eq(File(context.workingDirectory, "5.0")),
            any(),
            any(),
        )
        verify(remoteOps).downloadDirectory(
            eq(secondCassandraHost.toHost()),
            eq("/usr/local/cassandra/5.0.9-custom/conf"),
            eq(File(context.workingDirectory, "5.0.9-custom")),
            any(),
            any(),
        )
    }

    @Test
    fun `hosts on the same version download it once`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(hosts = mapOf(ServerType.Cassandra to listOf(testCassandraHost, secondCassandraHost))),
        )

        DownloadConfig().execute()

        verify(remoteOps, times(1)).downloadDirectory(any(), any(), any(), any(), any())
    }

    /** A failed download must not leave an empty directory that the next run takes for a finished one. */
    @Test
    fun `a failed download leaves no directory behind`() {
        whenever(remoteOps.downloadDirectory(any(), any(), any(), any(), any())).thenThrow(IllegalStateException("ssh dropped"))

        assertThatThrownBy { DownloadConfig().execute() }.hasMessageContaining("ssh dropped")

        assertThat(File(context.workingDirectory, "5.0")).doesNotExist()
    }
}
