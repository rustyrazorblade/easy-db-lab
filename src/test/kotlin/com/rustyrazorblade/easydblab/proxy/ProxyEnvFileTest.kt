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
    fun `records the Tailscale flag and the port as fixed keys only`() {
        envFile.recordTailscale(active = false)
        envFile.recordPort(41234)

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false", "EDL_SOCKS_PORT=41234")
    }

    @Test
    fun `recording the port keeps the Tailscale flag`() {
        envFile.recordTailscale(active = true)

        envFile.recordPort(41234)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = true, socksPort = 41234))
    }

    @Test
    fun `recording the Tailscale flag keeps the port`() {
        envFile.recordPort(41234)

        envFile.recordTailscale(active = false)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = 41234))
    }

    @Test
    fun `removing the port keeps the Tailscale flag`() {
        envFile.recordTailscale(active = false)
        envFile.recordPort(41234)

        envFile.removePort()

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false")
    }

    @Test
    fun `removing the port when no file exists writes nothing`() {
        envFile.removePort()

        assertThat(file).doesNotExist()
    }

    @Test
    fun `drops a line it does not own when it rewrites the file`() {
        file.writeText("EDL_TAILSCALE_ACTIVE=false\nrm -rf /tmp/x\nEDL_OTHER=1\n")

        envFile.recordPort(41234)

        assertThat(file.readLines()).containsExactly("EDL_TAILSCALE_ACTIVE=false", "EDL_SOCKS_PORT=41234")
    }

    @Test
    fun `replaces the file by rename, so a reader of the old file still sees all of it`() {
        envFile.recordTailscale(active = false)
        envFile.recordPort(41234)

        file.inputStream().use { oldReader ->
            envFile.recordPort(45678)

            assertThat(String(oldReader.readAllBytes())).isEqualTo("EDL_TAILSCALE_ACTIVE=false\nEDL_SOCKS_PORT=41234\n")
        }
        assertThat(envFile.read().socksPort).isEqualTo(45678)
        assertThat(workspace.list()).containsExactly(Constants.Proxy.ENV_FILE)
    }

    @Test
    fun `sources cleanly in sh`() {
        envFile.recordTailscale(active = false)
        envFile.recordPort(41234)

        val output = sh(". '${file.absolutePath}'; echo \"${'$'}EDL_TAILSCALE_ACTIVE:${'$'}EDL_SOCKS_PORT\"")

        assertThat(output).isEqualTo("false:41234")
    }

    @Test
    fun `concurrent writers never leave a partial file`() {
        envFile.recordTailscale(active = false)
        envFile.recordPort(PORT_BASE)
        val pool = Executors.newFixedThreadPool(WRITERS + 1)
        val start = CountDownLatch(1)
        val reads = mutableListOf<List<String>>()
        try {
            val writers =
                (1..WRITERS).map { writer ->
                    pool.submit {
                        start.await()
                        repeat(ROUNDS) { round -> ProxyEnvFile(workspace).recordPort(PORT_BASE + writer * ROUNDS + round) }
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
            assertThat(lines).hasSize(2)
            assertThat(lines[0]).isEqualTo("EDL_TAILSCALE_ACTIVE=false")
            assertThat(lines[1]).matches("EDL_SOCKS_PORT=\\d+")
        }
        assertThat(workspace.list()).containsExactly(Constants.Proxy.ENV_FILE)
    }

    @Test
    fun `reads nothing from a workspace with no file`() {
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = null, socksPort = null))
    }

    @Test
    fun `delete removes the file`() {
        envFile.recordTailscale(active = true)

        envFile.delete()

        assertThat(file).doesNotExist()
    }

    private fun sh(script: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertThat(process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).withFailMessage(output).isZero()
        return output
    }

    private companion object {
        const val WRITERS = 4
        const val ROUNDS = 50
        const val PORT_BASE = 40000
        const val LIMIT_SECONDS = 30L
    }
}
