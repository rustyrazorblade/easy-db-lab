package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.DefaultRemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.SSHClient
import org.apache.sshd.client.session.ClientSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Tests `cassandra nt` through the real remote-operations and SSH layers, so a host's output that
 * is printed both as it streams and under its `=== <alias> ===` header shows up twice.
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
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Cassandra to cassandraHosts),
        )

    /**
     * The real SSH stack down to the MINA session: each host's session answers every command with
     * that host's ring, so what reaches the output is whatever SSHClient and the command print.
     */
    private val connectionProvider =
        object : SSHConnectionProvider {
            override fun getConnection(host: Host): ISSHClient {
                val session = mock<ClientSession>()
                whenever(session.executeRemoteCommand(any(), any(), any(), any<Charset>())).thenAnswer { invocation ->
                    invocation.getArgument<OutputStream>(1).write(ringOf(host.alias).toByteArray())
                    null
                }
                return SSHClient(session)
            }

            override fun stop() {
                // Nothing to close: the sessions are mocks.
            }
        }

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
    fun `each host's output appears exactly once, under its header`() {
        val command = Nodetool()
        command.args = listOf("status")
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        cassandraHosts.map { it.alias }.forEach { alias ->
            assertThat(output.split(ringOf(alias)).size - 1)
                .describedAs("occurrences of $alias's output in:\n$output")
                .isEqualTo(1)
            assertThat(output).contains("=== $alias ===\n${ringOf(alias)}")
        }
    }
}
