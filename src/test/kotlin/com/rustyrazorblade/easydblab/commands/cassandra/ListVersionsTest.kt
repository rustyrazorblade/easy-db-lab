package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.FakeCassandraService
import com.rustyrazorblade.easydblab.services.cassandraversions.FakeNodes
import com.rustyrazorblade.easydblab.services.cassandraversions.RecordOnlySources
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionSources
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** `cassandra list` with and without a cluster; the table rules are in `VersionTableTest`. */
class ListVersionsTest : BaseKoinTest() {
    private val nodes = FakeNodes()
    private val stateManager = mock<ClusterStateManager>()
    private var sources: VersionSources = RecordOnlySources()
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single<RemoteOperationsService> { nodes }
                single { HostOperationsService(stateManager) }
                single<CassandraService> { FakeCassandraService() }
                single<VersionSources> { sources }
                single { CassandraInstaller(get(), get(), get(), get()) }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout, true))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    private fun withCluster() {
        whenever(stateManager.exists()).thenReturn(true)
        whenever(stateManager.load()).thenReturn(
            ClusterState(
                name = "c",
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Cassandra to listOf(FakeNodes.host("db0"), FakeNodes.host("db1"))),
            ),
        )
    }

    @Test
    fun `with no cluster it lists the sources, asks no node and exits normally`() {
        whenever(stateManager.exists()).thenReturn(false)

        ListVersions().execute()

        val output = stdout.toString()
        assertThat(output).contains("VERSION", "4.1", "5.0", "asf").doesNotContain("INSTALLED")
        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `in a cluster it marks what each node has`() {
        withCluster()
        nodes.disk("db0") += "5.0"
        nodes.disk("db1") += "5.0"
        nodes.disk("db1") += "mine"

        ListVersions().execute()

        val lines = stdout.toString().lines()
        assertThat(lines.first()).contains("INSTALLED")
        assertThat(lines.single { it.startsWith("5.0 ") }).contains(" all ")
        assertThat(lines.single { it.startsWith("mine ") }).contains("unknown", "db1")
    }

    @Test
    fun `a node that cannot be listed is named and the command exits non-zero, after the table`() {
        withCluster()
        nodes.unreachable += "db1"
        nodes.disk("db0") += "5.0"

        assertThatThrownBy { ListVersions().execute() }.isInstanceOf(CommandFailedException::class.java)

        val output = stdout.toString()
        assertThat(output).contains("db1: could not list versions", "timed out")
        assertThat(output.lines().single { it.startsWith("5.0 ") }).contains("db0").doesNotContain(" all ")
    }

    @Test
    fun `a stale source list is shown and says it is stale`() {
        whenever(stateManager.exists()).thenReturn(false)
        sources = RecordOnlySources(stale = true)

        ListVersions().execute()

        assertThat(stdout.toString()).contains("ASF list could not be fetched", "stale")
    }
}
