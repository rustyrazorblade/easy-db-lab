package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ListVersionsTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockCatalog: CassandraBuildCatalog
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
                single<CassandraBuildCatalog> { mockCatalog }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        mockCatalog = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockCatalog.list()).thenReturn(emptyList())
    }

    @Test
    fun `buildVersionList marks a lazy version that is not installed`() {
        val event =
            ListVersions().buildVersionList(
                installed = listOf("5.0", "trunk"),
                declared = listOf(lazyVersion("cep-45"), lazyVersion("trunk"), eagerVersion("6.0")),
            )

        assertThat(event.versions).containsExactly("5.0", "trunk")
        // trunk is declared lazy but already installed; 6.0 is declared but not lazy
        assertThat(event.declaredNotInstalled).containsExactly("cep-45")
        assertThat(event.toDisplayString())
            .contains("cep-45 (declared, not installed")
            .doesNotContain("trunk (declared")
    }

    @Test
    fun `buildVersionList reports only installed versions when nothing is declared lazily`() {
        val event = ListVersions().buildVersionList(installed = listOf("5.0"), declared = emptyList())

        assertThat(event.declaredNotInstalled).isEmpty()
        assertThat(event.toDisplayString()).isEqualTo("5.0")
    }

    @Test
    fun `buildVersionList offers a published build that is not installed yet`() {
        val event =
            ListVersions().buildVersionList(
                installed = listOf("5.0"),
                declared = emptyList(),
                builds = listOf("5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17"),
            )

        assertThat(event.builds).containsExactly("5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17")
        assertThat(event.toDisplayString())
            .contains("5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17 (build, not installed")
            .contains("cassandra install 5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17")
    }

    @Test
    fun `a build already installed on the cluster is not offered a second time`() {
        val build = "5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17"

        val event =
            ListVersions().buildVersionList(
                installed = listOf("5.0", build),
                declared = emptyList(),
                builds = listOf(build),
            )

        assertThat(event.builds).isEmpty()
        assertThat(event.toDisplayString()).doesNotContain("(build, not installed")
        // ...but it is still listed as installed, which is what the node actually has.
        assertThat(event.versions).contains(build)
    }

    private fun lazyVersion(version: String) =
        CassandraVersion(version = version, java = "11", python = "3.11.9", jvmOptions = null, lazy = true)

    private fun eagerVersion(version: String) = CassandraVersion(version = version, java = "11", python = "3.11.9", jvmOptions = null)

    @Test
    fun `execute lists versions excluding current`() {
        // RemoteOperationsService mock returns empty response by default
        // The command calls remoteOps.executeRemotely which returns Response("")
        val command = ListVersions()
        command.execute()

        // The mock SSH returns empty response, so nothing is listed
        // But the command should not throw
    }
}
