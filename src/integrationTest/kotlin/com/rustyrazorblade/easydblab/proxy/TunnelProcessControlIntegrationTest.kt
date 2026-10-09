package com.rustyrazorblade.easydblab.proxy

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
 * [TunnelProcessControl] against a real `ssh -N -D`, launched with the command line
 * [ProcessSocksProxyService] builds and the launcher it uses.
 *
 * The ssh never reaches a server: its `ProxyCommand` is a `sleep` that sends nothing, so ssh waits
 * for a banner and stays alive with its `-D` arguments, on any network. `BatchMode` keeps it from
 * prompting. Every process the test starts, the `sleep` children included, is ended afterwards.
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
    fun `a real recorded ssh tunnel is verified and stopped`() {
        val port = freePort()
        val ssh = launchTunnel(port)

        val result = TunnelProcessControl().stop(record(ssh.pid(), port))

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(ssh.pid().toInt()))
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
    }

    @Test
    fun `a real ssh with another -D port is not signaled, unless the OS reports no arguments`() {
        val ssh = launchTunnel(freePort())
        val argumentsReported = ssh.info().arguments().isPresent

        val result = TunnelProcessControl().stop(record(ssh.pid(), freePort()))

        if (argumentsReported) {
            assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
            assertThat(ssh.isAlive).isTrue()
        } else {
            // Documented fallback: with no arguments to compare, the ssh executable alone matches.
            assertThat(result).isEqualTo(TunnelStopResult.Stopped(ssh.pid().toInt()))
        }
    }

    /** Starts ssh exactly as [ProcessSocksProxyService] does, and waits until it is the ssh process. */
    private fun launchTunnel(port: Int): ProcessHandle {
        val command =
            ProcessSocksProxyService(Context.forCli(workspace).copy(workingDirectory = workspace), { _, _, _ -> false })
                .buildSshCommand(port, sshConfig.absolutePath, "control0")
        val handle = DefaultSshProcessLauncher.launch(command, File(workspace, "ssh.log")).toHandle()
        started += handle
        // nohup execs ssh in place; wait until the process reports ssh as its executable.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (handle
                .info()
                .command()
                .map { File(it).name }
                .orElse("") != "ssh"
        ) {
            check(handle.isAlive) { "ssh exited early: ${File(workspace, "ssh.log").readText()}" }
            check(System.nanoTime() < deadline) { "the process never became ssh" }
            Thread.sleep(POLL_MS)
        }
        return handle
    }

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
