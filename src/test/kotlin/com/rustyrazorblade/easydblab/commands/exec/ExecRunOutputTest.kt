package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import com.rustyrazorblade.easydblab.providers.ssh.DefaultRemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.MockSSHClient
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * What `exec run` prints: the unit's journal comes out through the real remote operations and
 * node output, one tagged line per journal line, for every host, also when hosts run in parallel.
 * Only the SSH session is a fake.
 */
class ExecRunOutputTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()

    private val dbHosts =
        (0 until 4).map { index ->
            ClusterHost(
                publicIp = "54.1.2.${index + 1}",
                privateIp = "10.0.1.${index + 1}",
                alias = "db$index",
                availabilityZone = "us-west-2a",
                instanceId = "i-db$index",
            )
        }

    /** One client per host: each answers the journal command with lines naming its own host. */
    private val clients =
        dbHosts.associate { host ->
            host.alias to
                MockSSHClient().apply {
                    onCommand = { command ->
                        if (command.contains("journalctl")) {
                            Response((1..50).joinToString("\n") { "${host.alias} line $it" })
                        } else {
                            Response("Running as unit: edl-exec-x.service")
                        }
                    }
                }
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<SSHConnectionProvider> {
                    object : SSHConnectionProvider {
                        override fun getConnection(host: Host): ISSHClient = clients.getValue(host.alias)

                        override fun discard(host: Host) = Unit

                        override fun stop() = Unit
                    }
                }
                single<RemoteOperationsService> { DefaultRemoteOperationsService(get()) }
                single { HostOperationsService(mockClusterStateManager) }
            },
        )

    @BeforeEach
    fun setupState() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Cassandra to dbHosts),
            ),
        )
    }

    @Test
    fun `a parallel run prints every journal line whole and tagged with its own host`() {
        ExecRun()
            .apply {
                command = listOf("uptime")
                parallel = true
            }.execute()

        val printed = getKoin().get<RecordingNodeOutput>().stdout.toList()
        val journal = printed.filterNot { it.contains("Running as unit") }
        assertThat(journal).hasSize(dbHosts.size * 50)
        assertThat(journal).allSatisfy { assertThat(it).matches("""\[(db\d)] \1 line \d+""") }
        dbHosts.forEach { host ->
            assertThat(journal.filter { it.startsWith("[${host.alias}] ") })
                .containsExactlyElementsOf((1..50).map { "[${host.alias}] ${host.alias} line $it" })
        }
        assertThat(printed).noneMatch { it.startsWith("===") }
    }

    /** A host whose unit printed nothing says so, so it is not mistaken for a host that was skipped. */
    @Test
    fun `a unit with an empty journal prints a no-output notice under its host`() {
        clients.getValue("db1").onCommand = { command ->
            if (command.contains("journalctl")) Response("") else Response("Running as unit: edl-exec-x.service")
        }

        ExecRun()
            .apply {
                command = listOf("true")
                hosts.hostList = "db1"
            }.execute()

        val printed = getKoin().get<RecordingNodeOutput>().stdout
        assertThat(printed).containsExactly("[db1] Running as unit: edl-exec-x.service", "[db1] no output")
    }

    /** The unit's journal is the only place its output is, and it is why the run failed. */
    @Test
    fun `a failed unit still prints its journal, tagged with the host, and the command fails`() {
        clients.getValue("db0").onCommand = { command ->
            when {
                command.contains("journalctl") -> Response("cat: /missing: No such file or directory")
                else -> throw RemoteCommandFailedException(command, "", "Finished with result: exit-code", "db0: exit status 3")
            }
        }

        assertThatThrownBy {
            ExecRun()
                .apply {
                    command = listOf("cat /missing")
                    hosts.hostList = "db0"
                }.execute()
        }.isInstanceOf(CommandFailedException::class.java)

        assertThat(getKoin().get<RecordingNodeOutput>().stdout)
            .containsExactly("[db0] cat: /missing: No such file or directory")
        // The failure names the host and why; the stderr it printed while running is not repeated.
        val reported = (getKoin().get<OutputHandler>() as BufferedOutputHandler).messages.joinToString("\n")
        assertThat(reported).contains("db0: command failed: db0: exit status 3")
        assertThat(reported).doesNotContain("Finished with result")
    }
}
