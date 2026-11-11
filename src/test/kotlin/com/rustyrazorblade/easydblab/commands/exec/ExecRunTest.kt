package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.rmi.ServerException

/**
 * Tests the systemd-run command line `exec run` sends to each host. The remote operations are
 * mocked because `systemd-run` exists only on the node; the command string is what can be wrong.
 */
class ExecRunTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockRemoteOps: RemoteOperationsService = mock()
    private val originalOut = System.out
    private val stdout = ByteArrayOutputStream()

    private val dbHosts =
        listOf("db0", "db1").mapIndexed { index, alias ->
            ClusterHost(
                publicIp = "54.1.2.${index + 1}",
                privateIp = "10.0.1.${index + 1}",
                alias = alias,
                availabilityZone = "us-west-2a",
                instanceId = "i-$alias",
            )
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<RemoteOperationsService> { mockRemoteOps }
                single { HostOperationsService(mockClusterStateManager) }
            },
        )

    @BeforeEach
    fun setupState() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Cassandra to dbHosts),
            ),
        )
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response(""))
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    private fun run(
        vararg command: String,
        configure: ExecRun.() -> Unit = {},
    ): List<String> {
        ExecRun()
            .apply {
                this.command = command.toList()
                configure()
            }.execute()
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(any(), captor.capture(), any(), any())
        return captor.allValues.filter { it.contains("systemd-run") }
    }

    @Test
    fun `a quoted command with arguments runs through a shell under a unit name with no quote in it`() {
        val sent = run("uname -n; uptime")

        assertThat(sent).hasSize(2)
        assertThat(sent).allSatisfy {
            assertThat(it).matches("""sudo systemd-run --unit=edl-exec-uname-\d+ --wait -- bash -c 'uname -n; uptime'""")
        }
    }

    @Test
    fun `a named run passes the whole command line to a shell, not as one executable name`() {
        val sent = run("uname -a") { unitNameOverride = "qa-uname" }

        assertThat(sent).allSatisfy {
            assertThat(it).isEqualTo("sudo systemd-run --unit=edl-exec-qa-uname --wait -- bash -c 'uname -a'")
        }
    }

    @Test
    fun `separate words keep their boundaries inside the shell command`() {
        val sent = run("grep", "a b", "/etc/hosts") { hosts.hostList = "db0" }

        assertThat(sent.single())
            .matches("""sudo systemd-run --unit=edl-exec-grep-\d+ --wait -- bash -c 'grep '\\''a b'\\'' /etc/hosts'""")
    }

    @Test
    fun `a unit name keeps only the characters systemd allows`() {
        val sent = run("uptime") { unitNameOverride = "my tool's run" }

        assertThat(sent).allSatisfy { assertThat(it).startsWith("sudo systemd-run --unit=edl-exec-my-tool-s-run --wait") }
    }

    @Test
    fun `a background run starts the unit without waiting`() {
        val sent = run("sleep 300") { background = true }

        assertThat(sent).allSatisfy {
            assertThat(it).matches("""sudo systemd-run --unit=edl-exec-sleep-\d+ -- bash -c 'sleep 300'""")
        }
    }

    @Test
    fun `a run that fails on one host still runs on the others, reports the failure, and fails the command`() {
        whenever(mockRemoteOps.executeRemotely(eq(dbHosts[0].toHost()), any(), any(), any()))
            .thenThrow(RuntimeException("Remote command failed (1)", ServerException("1")))

        assertThatThrownBy { ExecRun().apply { command = listOf("false") }.execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("db0")

        verify(mockRemoteOps, atLeastOnce()).executeRemotely(eq(dbHosts[1].toHost()), any(), any(), any())
        val output = (getKoin().get<OutputHandler>() as BufferedOutputHandler).messages.joinToString("\n")
        assertThat(output).contains("=== db0 ===\nError executing command: Remote command failed (1)")
    }

    /**
     * `systemd-run --wait` exits non-zero when the unit fails, and the unit's own output is only in
     * the journal, so a failed run still prints it: that output is the reason the run failed.
     */
    @Test
    fun `a failed foreground run prints the unit's journal before reporting the failure`() {
        whenever(mockRemoteOps.executeRemotely(eq(dbHosts[0].toHost()), argThat { contains("systemd-run") }, any(), any()))
            .thenThrow(RuntimeException("Remote command failed (3)"))
        whenever(mockRemoteOps.executeRemotely(eq(dbHosts[0].toHost()), argThat { contains("journalctl") }, any(), any()))
            .thenReturn(Response("cat: /missing: No such file or directory"))

        assertThatThrownBy {
            ExecRun()
                .apply {
                    command = listOf("cat /missing")
                    unitNameOverride = "qa-cat"
                    hosts.hostList = "db0"
                }.execute()
        }.isInstanceOf(CommandFailedException::class.java)

        val sent = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(eq(dbHosts[0].toHost()), sent.capture(), any(), any())
        assertThat(sent.allValues).contains("sudo journalctl --unit=edl-exec-qa-cat --no-pager --output=cat")
        assertThat(stdout.toString()).contains("=== db0 ===\ncat: /missing: No such file or directory")
        val events = (getKoin().get<OutputHandler>() as BufferedOutputHandler).messages.joinToString("\n")
        assertThat(events).contains("Error executing command: Remote command failed (3)")
    }
}
