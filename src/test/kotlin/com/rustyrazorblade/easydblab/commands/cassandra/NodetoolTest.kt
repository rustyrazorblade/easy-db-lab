package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import com.rustyrazorblade.easydblab.providers.ssh.DefaultRemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.SSHClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.io.IoSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.rmi.RemoteException

/**
 * Tests `cassandra nt` through the real remote-operations, SSH and node-output layers: each host's
 * output must come out once, every line tagged with that host, and never as an event.
 */
class NodetoolTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var hostOperationsService: HostOperationsService
    private lateinit var outputHandler: BufferedOutputHandler

    private val cassandraHosts =
        listOf("db0", "db1").mapIndexed { index, alias ->
            ClusterHost(
                publicIp = "54.1.2.${index + 1}",
                privateIp = "10.0.1.${index + 1}",
                alias = alias,
                availabilityZone = "us-west-2a",
                instanceId = "i-$alias",
            )
        }

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Cassandra to cassandraHosts),
        )

    /**
     * The real SSH stack down to the MINA session: each host's session answers every command with
     * that host's ring, so what reaches the output is whatever SSHClient and the command print.
     */
    private val connectionProvider =
        object : SSHConnectionProvider {
            override fun discard(host: Host) = Unit

            override fun getConnection(host: Host): ISSHClient {
                val ioSession = mock<IoSession>()
                whenever(ioSession.remoteAddress).thenReturn(InetSocketAddress(host.private, SSH_PORT))
                val session = mock<ClientSession>()
                whenever(session.ioSession).thenReturn(ioSession)
                whenever(session.executeRemoteCommand(any(), any(), any(), any<Charset>())).thenAnswer { invocation ->
                    invocation.getArgument<OutputStream>(1).write(ringOf(host.alias).toByteArray())
                    if (host.alias == failingAlias) {
                        invocation.getArgument<OutputStream>(2).write(LONG_ERROR.toByteArray())
                        throw RemoteException("Remote command failed (1): nodetool")
                    }
                    null
                }
                return SSHClient(session)
            }

            override fun stop() {
                // Nothing to close: the sessions are mocks.
            }
        }

    private var failingAlias: String? = null

    private fun ringOf(alias: String) = "Datacenter: us-west-2 ring-of-$alias"

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
                single<RemoteOperationsService> { DefaultRemoteOperationsService(connectionProvider) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `execute shows usage when no arguments provided`() {
        val command = Nodetool()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Usage:")
    }

    @Test
    fun `each host's output appears exactly once, tagged with the host`() {
        val command = Nodetool()
        command.args = listOf("status")
        command.execute()

        val printed = getKoin().get<RecordingNodeOutput>().stdout.toList()
        assertThat(printed).containsExactlyInAnyOrderElementsOf(
            cassandraHosts.map { "[${it.alias}] ${ringOf(it.alias)}" },
        )
        assertThat(outputHandler.messages.joinToString("\n")).doesNotContain("ring-of-")
    }

    /** The host's output printed tagged as it ran; the failure says which host and command, and does not repeat it. */
    @Test
    fun `a failed nodetool is reported by its one-line summary, not its output again`() {
        failingAlias = "db1"

        assertThatThrownBy { Nodetool().apply { args = listOf("status") }.execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("db1")
            .hasMessageContaining("Remote command failed (1)")
            .hasMessageNotContaining("error line")
        assertThat(getKoin().get<RecordingNodeOutput>().stderr).contains("[db1] error line 1")
    }

    private companion object {
        const val SSH_PORT = 22
        val LONG_ERROR = (1..40).joinToString("\n", postfix = "\n") { "error line $it" }
    }
}
