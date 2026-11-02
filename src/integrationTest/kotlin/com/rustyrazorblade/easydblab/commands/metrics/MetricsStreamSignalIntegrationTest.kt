package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.CliProcess
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.RedisTestSupport
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a real `metrics stream` process against a Redis container and stops it with SIGTERM, to see
 * what only a whole process shows: the exit status after the first and the second signal.
 *
 * The workspace is a Tailscale cluster whose control node is 127.0.0.1, so the stream starts no
 * tunnel and queries this machine's port 9009 directly.
 */
class MetricsStreamSignalIntegrationTest {
    @TempDir
    lateinit var root: File

    private val redis = RedisTestSupport.newContainer()
    private lateinit var workspace: File
    private lateinit var output: File
    private var process: Process? = null

    @BeforeEach
    fun prepare() {
        redis.start()
        workspace = File(root, "workspace").apply { mkdirs() }
        output = File(root, "out.log")
        val userDir = File(root, "user")
        UserConfigProvider(File(userDir, "profiles/default").apply { mkdirs() })
            .saveUserConfig(User("test@example.com", "us-west-2", "key", "", "access", "secret"))
        ClusterStateManager(File(workspace, "state.json")).save(
            ClusterState(
                name = "lab",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("127.0.0.1", "127.0.0.1", "control0", "us-west-2a", "i-1"))),
                tailscaleActive = true,
            ).apply { markInfrastructureUp() },
        )
    }

    @AfterEach
    fun cleanUp() {
        process?.destroyForcibly()
        redis.stop()
    }

    private fun startStream(): Process =
        CliProcess
            .start(
                workingDirectory = workspace,
                output = output,
                environment =
                    mapOf(
                        Constants.EventBus.REDIS_URL_ENV_VAR to RedisTestSupport.url(redis, "camera"),
                        Constants.Environment.USER_DIR to File(root, "user").absolutePath,
                    ),
                "metrics",
                "stream",
                "--metrics",
                "node.cpu",
            ).also { process = it }

    private fun <T> awaitUntil(
        what: String,
        ready: (T) -> Boolean,
        read: () -> T,
    ): T {
        val config =
            RetryConfig
                .custom<T>()
                .maxAttempts(STARTUP_ATTEMPTS)
                .waitDuration(POLL)
                .retryOnResult { !ready(it) }
                .build()
        val result = Retry.of(what, config).executeSupplier(read)
        check(ready(result)) { "$what did not happen; output:\n${output.readText()}" }
        return result
    }

    @Test
    fun `the first SIGTERM between cycles stops the stream with exit 0`() {
        val stream = startStream()
        awaitUntil("a completed cycle", { it.contains("metrics stream: published") }) { output.readText() }

        stream.destroy()

        assertThat(stream.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(stream.exitValue()).describedAs(output.readText()).isZero()
    }

    @Test
    fun `the first SIGTERM in the middle of a cycle stops the stream with exit 0 and nothing more`() {
        // Mimir accepts the query and never answers, so the signal arrives while the cycle waits on it.
        ServerSocket(Constants.K8s.MIMIR_HTTP_PORT, 1, InetAddress.getByName("127.0.0.1")).use { mimir ->
            val accepted = CompletableFuture<Socket>()
            thread(isDaemon = true) { runCatching { accepted.complete(mimir.accept()) } }
            val stream = startStream()
            accepted.get(STARTUP_SECONDS, TimeUnit.SECONDS).use {
                stream.destroy()

                assertThat(stream.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                assertThat(stream.exitValue()).describedAs(output.readText()).isZero()
                assertThat(output.readText()).doesNotContain("metrics stream: published")
            }
        }
    }

    private companion object {
        const val STARTUP_ATTEMPTS = 60
        const val STARTUP_SECONDS = 60L
        const val EXIT_WAIT_SECONDS = 20L
        val POLL: Duration = Duration.ofSeconds(1)
    }
}
