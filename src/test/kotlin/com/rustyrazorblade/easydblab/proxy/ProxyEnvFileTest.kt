package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** [ProxyEnvFile] is the only source of proxy state for shell-side tools, so its content must always be sourceable. */
internal class ProxyEnvFileTest {
    @TempDir
    lateinit var workspace: File

    private val envFile by lazy { ProxyEnvFile(workspace) }
    private val file: File get() = File(workspace, Constants.Proxy.ENV_FILE)

    @Test
    fun `records the network mode, the port and the tunnel PID as fixed keys only`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        envFile.recordTunnel(41234, PID)

        assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=socks", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")
    }

    @Test
    fun `each mode is written as its id and read back`() {
        NetworkMode.entries.forEach { mode ->
            envFile.recordNetworkMode(mode)

            assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=${mode.id}")
            assertThat(envFile.read().networkMode).isEqualTo(mode)
        }
    }

    @Test
    fun `recording the tunnel keeps the network mode`() {
        envFile.recordNetworkMode(NetworkMode.Direct)

        envFile.recordTunnel(41234, PID)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Direct, tunnel = RecordedTunnel(41234, PID)))
    }

    @Test
    fun `recording the network mode keeps the tunnel`() {
        envFile.recordTunnel(41234, PID)

        envFile.recordNetworkMode(NetworkMode.Tailscale)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Tailscale, tunnel = RecordedTunnel(41234, PID)))
    }

    @Test
    fun `an earlier release's EDL_TAILSCALE_ACTIVE reads as tailscale when true and socks when false`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=true\n")
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Tailscale)

        file.writeText("EDL_TAILSCALE_ACTIVE=false\n")
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Socks)
    }

    @Test
    fun `EDL_NETWORK_MODE wins over an EDL_TAILSCALE_ACTIVE in the same file`() {
        file.writeText("EDL_NETWORK_MODE=direct\nEDL_TAILSCALE_ACTIVE=true\n")

        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Direct)
    }

    @Test
    fun `a file with neither key, or an unknown mode, reads as no mode`() {
        file.writeText("EDL_SOCKS_PORT=41234\nEDL_SOCKS_PID=$PID\n")
        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = null, tunnel = RecordedTunnel(41234, PID)))

        file.writeText("EDL_NETWORK_MODE=vpn\n")
        assertThat(envFile.read().networkMode).isNull()
    }

    @Test
    fun `recording the mode converts an earlier release's file and keeps its tunnel`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=true\nEDL_SOCKS_PORT=41234\nEDL_SOCKS_PID=$PID\n")

        envFile.recordNetworkMode(NetworkMode.Tailscale)

        assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=tailscale", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")
    }

    @Test
    fun `recording or removing the tunnel keeps an earlier release's file in its old format`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=true\n")

        envFile.recordTunnel(41234, PID)
        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=true", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")

        envFile.removeTunnel()
        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=true")
    }

    @Test
    fun `recording the tunnel keeps an unknown mode as it found it`() {
        file.writeText("EDL_NETWORK_MODE=vpn\n")

        envFile.recordTunnel(41234, PID)

        assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=vpn", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")
    }

    @Test
    fun `direct mode sources cleanly in sh`() {
        envFile.recordNetworkMode(NetworkMode.Direct)

        val output = sh(". '${file.absolutePath}'; echo \"${'$'}EDL_NETWORK_MODE\"")

        assertThat(output).isEqualTo("direct")
    }

    @Test
    fun `re-recording an unchanged network mode writes nothing`() {
        envFile.recordNetworkMode(NetworkMode.Direct)
        envFile.recordTunnel(41234, PID)
        val before = inode()
        val lock = File(workspace, Constants.Proxy.ENV_LOCK_FILE).apply { delete() }

        envFile.recordNetworkMode(NetworkMode.Direct)
        envFile.recordTunnel(41234, PID)

        assertThat(inode()).isEqualTo(before)
        assertThat(lock).doesNotExist()
    }

    @Test
    fun `re-recording a changed network mode still replaces the file`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        val before = inode()

        envFile.recordNetworkMode(NetworkMode.Direct)

        assertThat(inode()).isNotEqualTo(before)
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Direct)
    }

    @Test
    fun `recording the same mode still converts an earlier release's file`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=false\n")

        envFile.recordNetworkMode(NetworkMode.Socks)

        assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=socks")
    }

    private fun inode(): Any = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    @Test
    fun `removing the tunnel removes its port and PID together and keeps the network mode`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        envFile.recordTunnel(41234, PID)

        envFile.removeTunnel()

        assertThat(file.readLines()).containsExactly("EDL_NETWORK_MODE=socks")
    }

    @Test
    fun `removing the tunnel when no file exists writes nothing`() {
        envFile.removeTunnel()

        assertThat(file).doesNotExist()
    }

    @Test
    fun `a port without a PID, or a PID without a port, is no recorded tunnel`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=false\nEDL_SOCKS_PORT=41234\n")
        assertThat(envFile.read().tunnel).isNull()

        file.writeText("EDL_TAILSCALE_ACTIVE=false\nEDL_SOCKS_PID=$PID\n")
        assertThat(envFile.read().tunnel).isNull()
    }

    @Test
    fun `drops a line it does not own when it rewrites the file`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=false\nrm -rf /tmp/x\nEDL_OTHER=1\n")

        envFile.recordTunnel(41234, PID)

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")
    }

    @Test
    fun `replaces the file by rename, so a reader of the old file still sees all of it`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        envFile.recordTunnel(41234, PID)

        file.inputStream().use { oldReader ->
            envFile.recordTunnel(45678, PID)

            assertThat(String(oldReader.readAllBytes())).isEqualTo("EDL_NETWORK_MODE=socks\nEDL_SOCKS_PORT=41234\nEDL_SOCKS_PID=$PID\n")
        }
        assertThat(envFile.read().tunnel).isEqualTo(RecordedTunnel(45678, PID))
        assertThat(workspace.list()).containsExactlyInAnyOrder(Constants.Proxy.ENV_FILE, Constants.Proxy.ENV_LOCK_FILE)
    }

    @Test
    fun `sources cleanly in sh`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        envFile.recordTunnel(41234, PID)

        val output = sh(". '${file.absolutePath}'; echo \"${'$'}EDL_NETWORK_MODE:${'$'}EDL_SOCKS_PORT:${'$'}EDL_SOCKS_PID\"")

        assertThat(output).isEqualTo("socks:41234:$PID")
    }

    @Test
    fun `concurrent writers never leave a partial file`() {
        envFile.recordNetworkMode(NetworkMode.Socks)
        envFile.recordTunnel(PORT_BASE, PID)
        val pool = Executors.newFixedThreadPool(WRITERS + 1)
        val start = CountDownLatch(1)
        val reads = mutableListOf<List<String>>()
        try {
            val writers =
                (1..WRITERS).map { writer ->
                    pool.submit {
                        start.await()
                        repeat(ROUNDS) { round -> ProxyEnvFile(workspace).recordTunnel(PORT_BASE + writer * ROUNDS + round, PID) }
                    }
                }
            val reader =
                pool.submit {
                    start.await()
                    repeat(ROUNDS * WRITERS) { reads += file.readLines() }
                }
            start.countDown()
            (writers + reader).forEach { it.get(LIMIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(reads).allSatisfy { lines ->
            assertThat(lines).hasSize(3)
            assertThat(lines[0]).isEqualTo("EDL_NETWORK_MODE=socks")
            assertThat(lines[1]).matches("EDL_SOCKS_PORT=\\d+")
            assertThat(lines[2]).isEqualTo("EDL_SOCKS_PID=$PID")
        }
        assertThat(workspace.list()).containsExactlyInAnyOrder(Constants.Proxy.ENV_FILE, Constants.Proxy.ENV_LOCK_FILE)
    }

    @Test
    fun `two threads writing different keys at once keep both`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val lost = AtomicInteger()
        try {
            val tunnelWriter =
                pool.submit {
                    start.await()
                    val writer = ProxyEnvFile(workspace)
                    for (port in 1..ROUNDS * WRITERS) {
                        writer.recordTunnel(port, PID)
                        if ((writer.read().tunnel?.port ?: 0) < port) lost.incrementAndGet()
                    }
                }
            val flagWriter =
                pool.submit {
                    start.await()
                    val writer = ProxyEnvFile(workspace)
                    repeat(ROUNDS * WRITERS) { round -> writer.recordNetworkMode(alternatingMode(round)) }
                }
            start.countDown()
            listOf(tunnelWriter, flagWriter).forEach { it.get(LIMIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        envFile.recordNetworkMode(NetworkMode.Tailscale)

        assertThat(lost.get()).withFailMessage("a tunnel update was overwritten by a network mode update").isZero()
        val expected = ProxyEnv(networkMode = NetworkMode.Tailscale, tunnel = RecordedTunnel(ROUNDS * WRITERS, PID))
        assertThat(envFile.read()).isEqualTo(expected)
    }

    @Test
    fun `two processes writing different keys at once keep both`() {
        val rounds = ROUNDS * WRITERS
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val child =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ProxyEnvFileWriterMain::class.java.name,
                workspace.absolutePath,
                "$rounds",
            ).redirectErrorStream(true).start()
        // This process writes the network mode for as long as the child writes the tunnel.
        var round = 0
        while (child.isAlive) {
            envFile.recordNetworkMode(alternatingMode(round++))
        }
        envFile.recordNetworkMode(NetworkMode.Tailscale)
        val output = child.inputStream.bufferedReader().readText()

        assertThat(child.exitValue()).withFailMessage("the child's tunnel update was lost: $output").isZero()
        val expected = ProxyEnv(networkMode = NetworkMode.Tailscale, tunnel = RecordedTunnel(rounds, ProxyEnvFileWriterMain.PID))
        assertThat(envFile.read()).isEqualTo(expected)
    }

    @Test
    fun `reads nothing from a workspace with no file`() {
        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = null, tunnel = null))
    }

    @Test
    fun `delete removes the file`() {
        envFile.recordNetworkMode(NetworkMode.Tailscale)

        envFile.delete()

        assertThat(file).doesNotExist()
    }

    private fun alternatingMode(round: Int): NetworkMode = if (round % 2 == 0) NetworkMode.Tailscale else NetworkMode.Socks

    private fun sh(script: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).withFailMessage(output).isZero()
        return output
    }

    private companion object {
        const val WRITERS = 4
        const val ROUNDS = 50
        const val PORT_BASE = 40000
        const val PID = 4242L
        const val LIMIT_SECONDS = 30L
    }
}
