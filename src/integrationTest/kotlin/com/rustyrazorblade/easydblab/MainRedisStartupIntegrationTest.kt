package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class MainRedisStartupIntegrationTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `exits 1 before any command, naming the variable and the URL, when Redis does not answer`() {
        val port = ServerSocket(0).use { it.localPort }
        val url = "redis://127.0.0.1:$port/events"
        val output = File(workspace, "out.log")

        val process =
            CliProcess.start(
                workingDirectory = workspace,
                output = output,
                environment =
                    mapOf(
                        Constants.EventBus.REDIS_URL_ENV_VAR to url,
                        Constants.Environment.USER_DIR to File(workspace, "user").absolutePath,
                    ),
                "version",
            )

        assertThat(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(output.readText()).contains(Constants.EventBus.REDIS_URL_ENV_VAR).contains(url)
    }

    @Test
    fun `exits 1 before any command, naming the variable and the redacted value, when the URL names no host`() {
        val output = File(workspace, "out.log")

        val process =
            CliProcess.start(
                workingDirectory = workspace,
                output = output,
                environment =
                    mapOf(
                        Constants.EventBus.REDIS_URL_ENV_VAR to "redis://camera:s3cret@/events",
                        Constants.Environment.USER_DIR to File(workspace, "user").absolutePath,
                    ),
                "version",
            )

        assertThat(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(output.readText())
            .contains(Constants.EventBus.REDIS_URL_ENV_VAR)
            .contains("redis://***@/events")
            .doesNotContain("s3cret")
            .doesNotContain("InstanceCreationException")
    }

    private companion object {
        const val WAIT_SECONDS = 60L
    }
}
