package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** One node's listing as the whole cluster's answer, through the real per-node [ListVersions.buildVersionList]. */
private fun ListVersions.buildVersionList(
    installed: List<String>,
    declared: List<CassandraVersion>,
    builds: List<String> = emptyList(),
) = buildVersionList(mapOf("node" to installed), emptyMap(), declared, builds)

class ListVersionsTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockCatalog: CassandraBuildCatalog
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
                single<CassandraBuildCatalog> { mockCatalog }
                single { HostOperationsService(mockClusterStateManager) }
                factory<RemoteOperationsService> { remoteOps }
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

    private fun host(alias: String) =
        ClusterHost(
            publicIp = "54.1.2.${alias.last()}",
            privateIp = "10.0.1.${alias.last()}",
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = "i-$alias",
        )

    private fun clusterOf(vararg aliases: String) = testClusterState.copy(hosts = mapOf(ServerType.Cassandra to aliases.map { host(it) }))

    private fun nodeLists(
        alias: String,
        listing: String,
    ) {
        whenever(remoteOps.executeRemotely(eq(host(alias).toHost()), eq("ls /usr/local/cassandra"), any(), any()))
            .thenReturn(Response(listing))
    }

    /** A homogeneous cluster prints exactly what one node would: no per-node noise. */
    @Test
    fun `a cluster where every node has the same versions lists them once`() {
        val event =
            ListVersions().buildVersionList(
                perHost = mapOf("db0" to listOf("5.0", "4.1"), "db1" to listOf("5.0", "4.1"), "db2" to listOf("5.0", "4.1")),
                unreachable = emptyMap(),
                declared = emptyList(),
                builds = emptyList(),
            )

        assertThat(event.versions).containsExactly("5.0", "4.1")
        assertThat(event.partial).isEmpty()
        assertThat(event.toDisplayString()).isEqualTo("5.0\n4.1")
    }

    @Test
    fun `a version on only some nodes is reported with the nodes that have it`() {
        val event =
            ListVersions().buildVersionList(
                perHost =
                    mapOf(
                        "db0" to listOf("5.0"),
                        "db1" to listOf("5.0"),
                        "db2" to listOf("5.0", "5.0.9-custom"),
                    ),
                unreachable = emptyMap(),
                declared = emptyList(),
                builds = emptyList(),
            )

        assertThat(event.versions).containsExactly("5.0")
        assertThat(event.partial).containsExactly(entry("5.0.9-custom", listOf("db2")))
        assertThat(event.toDisplayString())
            .contains("5.0.9-custom (installed on db2 only, not on db0, db1)")
    }

    @Test
    fun `a node that cannot be listed is named with its error and its versions are not assumed`() {
        val event =
            ListVersions().buildVersionList(
                perHost = mapOf("db0" to listOf("5.0")),
                unreachable = mapOf("db1" to "connection refused"),
                declared = emptyList(),
                builds = emptyList(),
            )

        assertThat(event.toDisplayString()).contains("db1: could not list versions: connection refused")
    }

    @Test
    fun `execute asks every node, not just the first`() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterOf("db0", "db1"))
        nodeLists("db0", "5.0\ncurrent\n")
        nodeLists("db1", "5.0\n5.0.9-custom\ncurrent\n")

        ListVersions().execute()

        assertThat(outputHandler.messages.joinToString("\n"))
            .contains("5.0.9-custom (installed on db1 only, not on db0)")
    }

    @Test
    fun `execute fails after reporting when a node cannot be listed`() {
        whenever(mockClusterStateManager.load()).thenReturn(clusterOf("db0", "db1"))
        nodeLists("db0", "5.0\n")
        whenever(remoteOps.executeRemotely(eq(host("db1").toHost()), eq("ls /usr/local/cassandra"), any(), any()))
            .thenThrow(IllegalStateException("connection refused"))

        assertThatThrownBy { ListVersions().execute() }.isInstanceOf(CommandFailedException::class.java)
        // The report is an error event (stderr), naming the node and its error; the versions it could list are in it too.
        assertThat(outputHandler.errors.joinToString("\n") { it.first })
            .contains("db1: could not list versions: connection refused", "5.0")
    }

    /** A node that could not be listed is not a node the version is "missing" from. */
    @Test
    fun `an unreachable node is left out of the nodes a partial version is missing from`() {
        val event =
            ListVersions().buildVersionList(
                perHost = mapOf("db0" to listOf("5.0"), "db1" to listOf("5.0", "5.0.9-custom")),
                unreachable = mapOf("db2" to "timed out"),
                declared = emptyList(),
                nodes = listOf("db0", "db1", "db2"),
            )

        assertThat(event.toDisplayString())
            .contains("5.0.9-custom (installed on db1 only, not on db0)")
            .contains("db2: could not list versions: timed out")
    }
}
