package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
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
        // Past the restart backoff: nothing starts a new ssh for the port.
        Thread.sleep(TimeUnit.SECONDS.toMillis(Constants.Proxy.TUNNEL_RESTART_BACKOFF_SECONDS + 1L))
        assertThat(sshProcessesFor(port)).isEmpty()
    }

    @Test
    fun `a real tunnel whose ssh dies starts ssh again on the same port`() {
        val port = freePort()
        val (tunnel, ssh) = launchTunnel(port, startupGraceSeconds = 0)

        ssh.destroy()
        val next = awaitSsh(tunnel) { it.pid() != ssh.pid() }

        assertThat(
            next
                .info()
                .arguments()
                .map { it.toList() }
                .orElse(emptyList()),
        ).containsSequence("-D", "$port")
        assertThat(tunnel.isAlive).isTrue()
    }

    @Test
    fun `a real tunnel script for another port is not signaled`() {
        val (tunnel, _) = launchTunnel(freePort())

        val result = TunnelProcessControl().stop(record(tunnel.pid(), freePort()))

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

    /**
     * Starts `edl-socks-tunnel` exactly as [ProcessSocksProxyService] does, and waits for its ssh.
     *
     * @param startupGraceSeconds replaces the production start-up grace, so a test can kill the first
     *   ssh at once and still see it restarted
     */
    private fun launchTunnel(
        port: Int,
        startupGraceSeconds: Int = Constants.Proxy.TUNNEL_STARTUP_GRACE_SECONDS,
    ): Pair<ProcessHandle, ProcessHandle> {
        ToolWrapperInstaller().install(workspace)
        val command =
            ProcessSocksProxyService(Context.forCli(workspace).copy(workingDirectory = workspace), { _, _, _ -> false })
                .buildTunnelCommand(port, sshConfig.absolutePath, "control0")
                .toMutableList()
                .apply { set(lastIndex - 1, "$startupGraceSeconds") }
        val tunnel = DefaultSshProcessLauncher.launch(command, File(workspace, "ssh.log")).toHandle()
        started += tunnel
        return tunnel to awaitSsh(tunnel) { true }
    }

    /** Waits until [tunnel] has an `ssh` descendant that matches [accept], and returns it. */
    private fun awaitSsh(
        tunnel: ProcessHandle,
        accept: (ProcessHandle) -> Boolean,
    ): ProcessHandle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (true) {
            tunnel
                .descendants()
                .filter { isSsh(it) && accept(it) }
                .findFirst()
                .orElse(null)
                ?.let { return it }
            check(tunnel.isAlive) { "the tunnel script exited early: ${File(workspace, "ssh.log").readText()}" }
            check(System.nanoTime() < deadline) { "the tunnel script never ran ssh" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun isSsh(handle: ProcessHandle) =
        handle
            .info()
            .command()
            .map { File(it).name }
            .orElse("") == "ssh"

    /** Every live ssh process whose arguments carry `-D <port>`. */
    private fun sshProcessesFor(port: Int): List<ProcessHandle> =
        ProcessHandle
            .allProcesses()
            .filter { isSsh(it) }
            .filter { handle ->
                val arguments =
                    handle
                        .info()
                        .arguments()
                        .map { it.toList() }
                        .orElse(emptyList())
                arguments.indices.any { arguments[it] == "-D" && arguments.getOrNull(it + 1) == "$port" }
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LIMIT_SECONDS = 15L
        const val POLL_MS = 50L
    }
}
