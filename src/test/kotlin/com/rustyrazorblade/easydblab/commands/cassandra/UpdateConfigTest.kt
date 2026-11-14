package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class UpdateConfigTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockRemoteOps: RemoteOperationsService
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
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        mockRemoteOps = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockRemoteOps.executeRemotely(any<Host>(), any(), any(), any())).doReturn(Response(""))
        whenever(mockRemoteOps.executeRemotely(any<Host>(), argThat { contains("patch-config") }, any(), any()))
            .doReturn(Response("Successfully patched cassandra.yaml"))
        whenever(mockRemoteOps.executeRemotely(any<Host>(), argThat { contains("mktemp") }, any(), any()))
            .doReturn(Response("/tmp/easydblab.12345"))
        whenever(mockRemoteOps.getRemoteVersion(any(), any()))
            .doReturn(Version("/usr/local/cassandra/5.0"))

        // The version's config directory, which download-config creates in the workspace.
        File(context.workingDirectory, "5.0").mkdirs()

        // Create required patch file
        File(context.workingDirectory, "cassandra.patch.yaml").writeText(
            """
            cluster_name: test-cluster
            num_tokens: 4
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanup() {
        File(context.workingDirectory, "cassandra.patch.yaml").delete()
    }

    @Test
    fun `execute uploads config to cassandra hosts`() {
        val command = UpdateConfig()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Uploading cassandra.patch.yaml")
    }

    /**
     * A host on a build that has no config directory in the workspace used to be patched first and
     * then fail with a bare "Local directory does not exist". It now fails before touching the
     * host, and names the command that creates the directory.
     */
    @Test
    fun `a missing config directory fails before the host is patched and names download-config`() {
        File(context.workingDirectory, "5.0").deleteRecursively()
        whenever(mockRemoteOps.getRemoteVersion(any(), any()))
            .doReturn(Version("/usr/local/cassandra/5.0.9-custom"))

        assertThatThrownBy { UpdateConfig().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContainingAll("5.0.9-custom", "download-config --hosts db0")
        verify(mockRemoteOps, never()).executeRemotely(any<Host>(), argThat { contains("patch-config") }, any(), any())
    }

    /** On a multi-host run no host is changed unless every targeted host's directory is there. */
    @Test
    fun `one host without a config directory stops the whole run before any host is patched`() {
        val second = testCassandraHost.copy(alias = "db1", privateIp = "10.0.1.2", instanceId = "i-db1")
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(hosts = mapOf(ServerType.Cassandra to listOf(testCassandraHost, second))),
        )
        whenever(mockRemoteOps.getRemoteVersion(eq(second.toHost()), any()))
            .doReturn(Version("/usr/local/cassandra/5.0.9-custom"))

        assertThatThrownBy { UpdateConfig().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContainingAll("5.0.9-custom", "db1", "download-config --hosts db1")
        verify(mockRemoteOps, never()).executeRemotely(any<Host>(), argThat { contains("patch-config") }, any(), any())
    }

    /** Hosts are named by alias, as an operator knows them, never as the internal Host object. */
    @Test
    fun `progress output names each host by its alias`() {
        UpdateConfig().execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains(
            "Uploading cassandra.patch.yaml to db0",
            "Patching db0",
            "Created temporary directory /tmp/easydblab.12345 on db0",
            "Configuration updated for db0",
        )
        assertThat(output).doesNotContain("Host(", "i-db0", "54.1.2.3")
    }
}
