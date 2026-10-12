package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * [TunnelProcessControl] against a real `edl-socks-tunnel` running a real `ssh -N -D`, launched with
 * the command line [ProcessSocksProxyService] builds and the launcher it uses.
 *
 * The ssh never reaches a server: its `ProxyCommand` is a `sleep` that sends nothing, so ssh waits
 * for a banner and stays alive with its `-D` arguments, on any network. `BatchMode` keeps it from
 * prompting. Every process the test starts, the ssh and `sleep` children included, is ended afterwards.
 */
internal class TunnelProcessControlIntegrationTest {
    @TempDir
    lateinit var workspace: File

    private val started = mutableListOf<ProcessHandle>()

    private val sshConfig: File by lazy {
        File(workspace, "sshConfig").apply {
            writeText(
                """
                |Host control0
                |  Hostname 192.0.2.1
                |  User ubuntu
                |  ProxyCommand sleep 300
                |  BatchMode yes
                |  IdentitiesOnly yes
                |  IdentityAgent none
                |  StrictHostKeyChecking no
                |  UserKnownHostsFile /dev/null
                |
                """.trimMargin(),
            )
        }
    }

    @AfterEach
    fun endEverythingStarted() {
        started.forEach { handle ->
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        }
    }

    @Test
    fun `a real recorded tunnel is verified and stopped, and neither the script nor its ssh is left`() {
        val port = freePort()
        val (tunnel, ssh) = launchTunnel(port)

        val result = TunnelProcessControl().stop(record(tunnel.pid(), port))

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(tunnel.pid().toInt()))
        assertThat(tunnel.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        // The script has exited, so nothing is left to start a new ssh for the port.
        assertThat(sshProcessesFor(port)).isEmpty()
    }

    @Test
    fun `a real verified tunnel whose ssh dies starts ssh again on the same port`() {
        val port = freePort()
        val (tunnel, ssh) = launchTunnel(port)
        // What the CLI writes once it has verified the tunnel; the script restarts only then.
        ProxyEnvFile(workspace).recordTunnel(port, tunnel.pid())

        ssh.destroy()
        val next = awaitSsh(tunnel, port) { it.pid() != ssh.pid() }

        assertThat(
            next
                .info()
                .arguments()
                .map { it.toList() }
                .orElse(emptyList()),
        ).containsSequence("-D", "${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:$port")
        assertThat(tunnel.isAlive).isTrue()
    }

    @Test
    fun `a real script killed with KILL leaves an orphaned ssh, and stopping the recorded tunnel ends it`() {
        val port = freePort()
        val (tunnel, ssh) = launchTunnel(port)
        // Once its script is gone the ssh is no one's descendant, so it is ended on its own afterwards.
        started += ssh
        tunnel.destroyForcibly()
        assertThat(tunnel.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        assertThat(ssh.isAlive).withFailMessage("KILL on the script was expected to leave its ssh running").isTrue()

        val result = TunnelProcessControl().stop(record(tunnel.pid(), port))

        // The PID reported is the one stopped: the orphaned ssh, not the script already gone.
        assertThat(result).isEqualTo(TunnelStopResult.Stopped(ssh.pid().toInt()))
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        assertThat(sshProcessesFor(port)).isEmpty()
    }

    @Test
    fun `a real orphaned ssh for another port is not signaled`() {
        val port = freePort()
        val (tunnel, ssh) = launchTunnel(port)
        started += ssh
        tunnel.destroyForcibly()
        assertThat(tunnel.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()

        val result = TunnelProcessControl().stop(record(tunnel.pid(), otherPortThan(port)))

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(ssh.isAlive).isTrue()
    }

    @Test
    fun `a real tunnel script for another port is not signaled`() {
        val port = freePort()
        val (tunnel, _) = launchTunnel(port)

        val result = TunnelProcessControl().stop(record(tunnel.pid(), otherPortThan(port)))

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(tunnel.isAlive).isTrue()
    }

    @Test
    fun `a real bare ssh for the recorded port is not signaled`() {
        val port = freePort()
        val (_, ssh) = launchTunnel(port)

        val result = TunnelProcessControl().stop(record(ssh.pid(), port))

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(ssh.isAlive).isTrue()
    }

    /** Starts `edl-socks-tunnel` exactly as [ProcessSocksProxyService] does, and waits for its ssh. */
    private fun launchTunnel(port: Int): Pair<ProcessHandle, ProcessHandle> {
        TunnelTestProcesses.linkScript(installedScript, workspace)
        val command =
            ProcessSocksProxyService(Context.forCli(workspace).copy(workingDirectory = workspace), { _, _, _ -> false }, EventBus())
                .buildTunnelCommand(port, sshConfig.absolutePath, logFile, "control0")
        val tunnel = DefaultSshProcessLauncher.launch(command, logFile).toHandle()
        started += tunnel
        return tunnel to awaitSsh(tunnel, port)
    }

    private fun awaitSsh(
        tunnel: ProcessHandle,
        port: Int,
        accept: (ProcessHandle) -> Boolean = { true },
    ): ProcessHandle = TunnelTestProcesses.awaitSsh(tunnel, port, logFile, accept)

    /**
     * Every live process whose arguments carry `-N` and `-D 127.0.0.1:<port>`: an ssh of the tunnel,
     * found by its arguments alone, so a process whose executable the OS does not report still counts.
     */
    private fun sshProcessesFor(port: Int): List<ProcessHandle> =
        ProcessHandle
            .allProcesses()
            .filter { handle ->
                val arguments = TunnelTestProcesses.arguments(handle)
                "-N" in arguments &&
                    arguments.zipWithNext().any { (option, value) ->
                        option == "-D" && value == "${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:$port"
                    }
            }.toList()

    private fun record(
        pid: Long,
        port: Int,
    ) = Socks5ProxyStateFile(
        pid = pid.toInt(),
        port = port,
        controlHost = "control0",
        controlIP = "192.0.2.1",
        clusterName = workspace.name,
        startTime = Instant.now().toString(),
        sshConfig = sshConfig.absolutePath,
    )

    private fun freePort(): Int = TunnelTestProcesses.freeTunnelPort()

    private fun otherPortThan(port: Int): Int = generateSequence { freePort() }.first { it != port }

    private val logFile: File get() = File(workspace, "ssh.log")

    companion object {
        /** How long a test waits for a process it signaled to exit. */
        private const val LIMIT_SECONDS = 15L

        /** The tunnel script, installed once for the class; each test's workspace links to it. */
        private lateinit var installedScript: File

        @BeforeAll
        @JvmStatic
        fun installScript() {
            installedScript = TunnelTestProcesses.installScriptOnce()
        }

        @AfterAll
        @JvmStatic
        fun removeScript() {
            installedScript.deleteRecursively()
        }
    }
}
