package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.CliProcess
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Runs a real `easy-db-lab server` process and reads what it prints and writes: the ready output
 * names the status and API docs URLs and no `/sse` URL, `/sse` is not served, and no `.mcp.json`
 * is written.
 */
class ServerProcessIntegrationTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `the server prints the REST URLs, serves no sse endpoint and writes no mcp json`() {
        val workspace = File(root, "workspace").apply { mkdirs() }
        val userDir = File(root, "user")
        UserConfigProvider(File(userDir, "profiles/default").apply { mkdirs() })
            .saveUserConfig(User("test@example.com", "us-west-2", "key", "", "access", "secret"))
        val output = File(root, "out.log")
        val process =
            CliProcess.start(
                workingDirectory = workspace,
                output = output,
                environment = mapOf(Constants.Environment.USER_DIR to userDir.absolutePath),
                "server",
                "--port",
                "0",
            )
        try {
            val statusUrl = awaitStatusUrl(output)
            val base = statusUrl.removeSuffix("/status")
            val http = HttpClient.newHttpClient()

            val sse = http.send(HttpRequest.newBuilder(URI("$base/sse")).GET().build(), HttpResponse.BodyHandlers.ofString())

            assertThat(sse.statusCode()).isEqualTo(NOT_FOUND)
            assertThat(output.readText()).contains("$base/swagger").doesNotContain("/sse")
            assertThat(File(workspace, ".mcp.json")).doesNotExist()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun awaitStatusUrl(output: File): String {
        val config =
            RetryConfig
                .custom<String>()
                .maxAttempts(STARTUP_ATTEMPTS)
                .waitDuration(Duration.ofSeconds(1))
                .retryOnResult { it.isEmpty() }
                .build()
        val url =
            Retry.of("server-ready", config).executeSupplier {
                STATUS_URL.find(output.readText())?.value.orEmpty()
            }
        check(url.isNotEmpty()) { "the server never printed its status URL; output:\n${output.readText()}" }
        return url
    }

    private companion object {
        const val STARTUP_ATTEMPTS = 60
        const val NOT_FOUND = 404
        val STATUS_URL = Regex("""http://127\.0\.0\.1:\d+/status""")
    }
}
