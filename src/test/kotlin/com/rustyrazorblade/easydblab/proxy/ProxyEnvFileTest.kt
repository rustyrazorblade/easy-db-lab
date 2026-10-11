package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [ProxyEnvFile] is the only source of proxy state for shell-side tools, so its content must always be sourceable. */
internal class ProxyEnvFileTest {
    @TempDir
    lateinit var workspace: File

    private val envFile by lazy { ProxyEnvFile(workspace) }
    private val file: File get() = File(workspace, Constants.Proxy.ENV_FILE)

    @Test
    fun `records the Tailscale flag, the port and the tunnel PID as fixed keys only`() {
        envFile.recordTailscale(active = false)
        envFile.recordTunnel(41234, PID)

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false", "EDL_SOCKS_PORT=41234", "EDL_SOCKS_PID=$PID")
    }

    @Test
    fun `recording the tunnel keeps the Tailscale flag`() {
        envFile.recordTailscale(active = true)

        envFile.recordTunnel(41234, PID)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = true, tunnel = RecordedTunnel(41234, PID)))
    }

    @Test
    fun `recording the Tailscale flag keeps the tunnel`() {
        envFile.recordTunnel(41234, PID)

        envFile.recordTailscale(active = false)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = RecordedTunnel(41234, PID)))
    }

    @Test
    fun `removing the tunnel removes its port and PID together and keeps the Tailscale flag`() {
        envFile.recordTailscale(active = false)
        envFile.recordTunnel(41234, PID)

        envFile.removeTunnel()

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false")
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
        envFile.recordTailscale(active = false)
        envFile.recordTunnel(41234, PID)

        file.inputStream().use { oldReader ->
            envFile.recordTunnel(45678, PID)

            assertThat(String(oldReader.readAllBytes())).isEqualTo("EDL_TAILSCALE_ACTIVE=false\nEDL_SOCKS_PORT=41234\nEDL_SOCKS_PID=$PID\n")
        }
        assertThat(envFile.read().tunnel).isEqualTo(RecordedTunnel(45678, PID))
        assertThat(workspace.list()).containsExactly(Constants.Proxy.ENV_FILE)
    }

    @Test
    fun `sources cleanly in sh`() {
        envFile.recordTailscale(active = false)
        envFile.recordTunnel(41234, PID)

        val output = sh(". '${file.absolutePath}'; echo \"${'$'}EDL_TAILSCALE_ACTIVE:${'$'}EDL_SOCKS_PORT:${'$'}EDL_SOCKS_PID\"")

        assertThat(output).isEqualTo("false:41234:$PID")
    }

    @Test
    fun `concurrent writers never leave a partial file`() {
        envFile.recordTailscale(active = false)
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
            assertThat(lines[0]).isEqualTo("EDL_TAILSCALE_ACTIVE=false")
            assertThat(lines[1]).matches("EDL_SOCKS_PORT=\\d+")
            assertThat(lines[2]).isEqualTo("EDL_SOCKS_PID=$PID")
        }
        assertThat(workspace.list()).containsExactly(Constants.Proxy.ENV_FILE)
    }

    @Test
    fun `reads nothing from a workspace with no file`() {
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = null, tunnel = null))
    }

    @Test
    fun `delete removes the file`() {
        envFile.recordTailscale(active = true)

        envFile.delete()

        assertThat(file).doesNotExist()
    }

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
