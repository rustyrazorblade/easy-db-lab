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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A real `edl-socks-tunnel` running a real `ssh -N -D` to a real `sshd`, in a container, so the tunnel
 * carries traffic: the probe the CLI verifies a tunnel with reaches the container's own `sshd` through it.
 *
 * It shows what a stub `ssh` cannot: INT leaves the script and its `ssh` running with the tunnel still
 * carrying traffic, and an `ssh` restarted after another process took the port on 127.0.0.1 cannot run
 * half-bound on `::1`, so the script ends.
 */
internal class TunnelOverSshdIntegrationTest {
    @TempDir
    lateinit var workspace: File

    private val started = mutableListOf<ProcessHandle>()
    private val probe = SocksTunnelReachabilityProbe(Constants.Proxy.PROBE_ATTEMPT_TIMEOUT_MS)
    private val logFile: File get() = File(workspace, "ssh.log")

    @AfterEach
    fun endEverythingStarted() {
        started.forEach { handle ->
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        }
    }

    @Test
    fun `INT to the script and its ssh ends neither, and the tunnel still carries traffic`() {
        val port = freePort()
        val tunnel = launchTunnel(port)
        val ssh = awaitSsh(tunnel)
        awaitReachable(port)

        listOf(tunnel, ssh).forEach { process ->
            assertThat(ProcessBuilder("kill", "-INT", "${process.pid()}").start().waitFor()).isZero()
        }
        // The script and its ssh must stay alive, so there is no exit to wait for; give INT time to act.
        Thread.sleep(INT_SETTLE_MS)

        assertThat(tunnel.isAlive).withFailMessage("INT ended the script").isTrue()
        assertThat(ssh.isAlive).withFailMessage("INT ended ssh").isTrue()
        assertThat(probe.isReachable(port, SSHD_HOST, SSHD_PORT)).withFailMessage("the tunnel stopped carrying traffic after INT").isTrue()
    }

    @Test
    fun `a restart that finds 127 0 0 1 taken on the port ends the script instead of binding only ipv6`() {
        val port = freePort()
        val tunnel = launchTunnel(port)
        val ssh = awaitSsh(tunnel)
        awaitReachable(port)
        assertThat(arguments(ssh)).containsSequence("-D", "${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:$port")
        // What the CLI writes once it has verified the tunnel; the script restarts ssh only then.
        ProxyEnvFile(workspace).recordTunnel(port, tunnel.pid())

        ssh.destroy()
        assertThat(ssh.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive).isFalse()
        // Another process takes the port on 127.0.0.1 only, during the restart backoff.
        takeIpv4Loopback(port).use {
            assertThat(tunnel.onExit().get(LIMIT_SECONDS, TimeUnit.SECONDS).isAlive)
                .withFailMessage("the script kept running with 127.0.0.1:$port taken: ${logFile.readText()}")
                .isFalse()
        }

        val log = logFile.readText()
        assertThat(log).contains("cannot listen to port: $port")
        assertThat(log).contains("because port $port is taken by another process; not restarting.")
    }

    /** Starts `edl-socks-tunnel` exactly as [ProcessSocksProxyService] does. */
    private fun launchTunnel(port: Int): ProcessHandle {
        ToolWrapperInstaller().install(workspace)
        val command =
            ProcessSocksProxyService(Context.forCli(workspace).copy(workingDirectory = workspace), { _, _, _ -> false }, EventBus())
                .buildTunnelCommand(port, writeSshConfig().absolutePath, logFile, HOST_ALIAS)
        return DefaultSshProcessLauncher.launch(command, logFile).toHandle().also { started += it }
    }

    private fun writeSshConfig(): File =
        File(workspace, "sshConfig").apply {
            writeText(
                """
                |Host $HOST_ALIAS
                |  Hostname ${sshd.host}
                |  Port ${sshd.getMappedPort(SSHD_PORT)}
                |  User root
                |  IdentityFile ${privateKey.absolutePath}
                |  IdentitiesOnly yes
                |  IdentityAgent none
                |  BatchMode yes
                |  StrictHostKeyChecking no
                |  UserKnownHostsFile /dev/null
                |
                """.trimMargin(),
            )
        }

    /** Waits until [tunnel] runs an `ssh`, and returns it. */
    private fun awaitSsh(tunnel: ProcessHandle): ProcessHandle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (true) {
            tunnel
                .descendants()
                .filter { handle ->
                    handle
                        .info()
                        .command()
                        .map { File(it).name == "ssh" }
                        .orElse(false)
                }.findFirst()
                .orElse(null)
                ?.let { return it }
            check(tunnel.isAlive) { "the tunnel script exited early: ${logFile.readText()}" }
            check(System.nanoTime() < deadline) { "the tunnel script never ran ssh" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Waits until a SOCKS connection through [port] reaches the container's own `sshd`. */
    private fun awaitReachable(port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (!probe.isReachable(port, SSHD_HOST, SSHD_PORT)) {
            check(System.nanoTime() < deadline) { "the tunnel on port $port never carried traffic: ${logFile.readText()}" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Binds [port] on 127.0.0.1 only, retrying while the ssh that held it lets it go. */
    private fun takeIpv4Loopback(port: Int): ServerSocket {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (true) {
            try {
                return ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
            } catch (e: IOException) {
                check(System.nanoTime() < deadline) { "127.0.0.1:$port never became free: ${e.message}" }
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun arguments(handle: ProcessHandle): List<String> =
        handle
            .info()
            .arguments()
            .map { it.toList() }
            .orElse(emptyList())

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val HOST_ALIAS = "control0"
        private const val SSHD_PORT = 22

        /** The address the tunnel's far end connects to: the container's own `sshd`, as the CLI's probe does. */
        private const val SSHD_HOST = "127.0.0.1"
        private const val LIMIT_SECONDS = 30L
        private const val POLL_MS = 100L
        private const val INT_SETTLE_MS = 1500L

        /** An Alpine `sshd` that takes the test's key for root and allows the dynamic forward. */
        private val SSHD_DOCKERFILE =
            """
            FROM alpine:3.20
            RUN apk add --no-cache openssh-server \
             && ssh-keygen -A \
             && sed -i -E 's/^#?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config \
             && mkdir -p /root/.ssh \
             && chmod 700 /root/.ssh
            CMD ["/usr/sbin/sshd", "-D", "-e", "-o", "AllowTcpForwarding=yes", "-o", "PermitRootLogin=prohibit-password", "-o", "PasswordAuthentication=no"]
            """.trimIndent()

        private lateinit var keyDirectory: File
        private lateinit var privateKey: File
        private lateinit var sshd: GenericContainer<*>

        @BeforeAll
        @JvmStatic
        fun startSshd() {
            keyDirectory = Files.createTempDirectory("edl-sshd-key").toFile()
            privateKey = File(keyDirectory, "id_ed25519")
            val keygen = ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", privateKey.absolutePath).inheritIO().start()
            check(keygen.waitFor() == 0) { "ssh-keygen failed" }
            val publicKey = File(keyDirectory, "id_ed25519.pub").readText()
            sshd =
                GenericContainer(ImageFromDockerfile("edl-test-sshd", false).withFileFromString("Dockerfile", SSHD_DOCKERFILE))
                    .withExposedPorts(SSHD_PORT)
                    .withCopyToContainer(Transferable.of(publicKey, AUTHORIZED_KEYS_MODE), "/root/.ssh/authorized_keys")
                    .waitingFor(Wait.forListeningPort())
            sshd.start()
        }

        @AfterAll
        @JvmStatic
        fun stopSshd() {
            sshd.stop()
            keyDirectory.deleteRecursively()
        }

        /** `0600`: sshd refuses an `authorized_keys` others can write. */
        private const val AUTHORIZED_KEYS_MODE = 384
    }
}
