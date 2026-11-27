package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.CliProcess
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.ServerType
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
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Runs a real `metrics stream` process against a Redis container and stops it with SIGTERM, to see
 * what only a whole process shows: the exit status after the first and the second signal.
 *
 * The workspace is a Tailscale cluster whose control node is 127.0.0.1, so the stream starts no
 * tunnel and queries this machine's port 9009 directly.  That port is the machine's, not the
 * test's: every test here holds [MachinePortLock] on it until its stream has ended, so a stream from
 * another build running this class at the same time can never reach this test's fake Mimir.
 */
class MetricsStreamSignalIntegrationTest {
    @TempDir
    lateinit var root: File

    private val redis = RedisTestSupport.newContainer()
    private lateinit var workspace: File
    private lateinit var output: File
    private var process: Process? = null
    private lateinit var mimirPort: MachinePortLock

    @BeforeEach
    fun prepare() {
        // Held before any stream starts: every stream here queries 127.0.0.1:9009, a fake Mimir or not.
        mimirPort = MachinePortLock.acquire(Constants.K8s.MIMIR_HTTP_PORT)
        redis.start()
        workspace = File(root, "workspace").apply { mkdirs() }
        output = File(root, "out.log")
        val userDir = File(root, "user")
        TestProfiles.storeWith(File(userDir, "profiles/default").apply { mkdirs() })
        ClusterStateManager(File(workspace, "state.json")).replace(
            ClusterState(
                name = "lab",
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("127.0.0.1", "127.0.0.1", "control0", "us-west-2a", "i-1"))),
                networkMode = NetworkMode.Tailscale,
            ).apply { markInfrastructureUp() },
        )
    }

    @AfterEach
    fun cleanUp() {
        // The stream must be gone before the port is released, or it can reach the next holder's Mimir.
        process?.destroyForcibly()?.waitFor()
        redis.stop()
        mimirPort.close()
    }

    private fun startStream(redisUrl: String = RedisTestSupport.url(redis, "camera")): Process =
        CliProcess
            .start(
                workingDirectory = workspace,
                output = output,
                environment =
                    mapOf(
                        Constants.EventBus.REDIS_URL_ENV_VAR to redisUrl,
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
        // The signal comes at once, while the cycle waits on a Mimir that accepted the query.
        stopsWhileMimirNeverAnswers(afterRequestSent = false)
    }

    @Test
    fun `the first SIGTERM while a query is blocked reading its answer stops the stream before the read timeout`() {
        // A signal right after the connection can land before the query starts to read, and then the
        // thread's interrupt is seen at once.  Once the whole request has reached Mimir, the query is
        // at, or a few microseconds from, a blocking socket read, which only a cancelled call can end;
        // the read timeout is 30 s, longer than the exit wait.
        stopsWhileMimirNeverAnswers(afterRequestSent = true)
    }

    /**
     * Mimir accepts the query and never answers.  The signal comes right after the accept, or, with
     * [afterRequestSent], once Mimir has read the request's headers off the socket.
     */
    private fun stopsWhileMimirNeverAnswers(afterRequestSent: Boolean) {
        ServerSocket(Constants.K8s.MIMIR_HTTP_PORT, 1, InetAddress.getByName("127.0.0.1")).use { mimir ->
            val accepted = CompletableFuture<Socket>()
            thread(isDaemon = true) { runCatching { accepted.complete(mimir.accept()) } }
            val stream = startStream()
            awaitMimirConnection(accepted, stream).use { connection ->
                if (afterRequestSent) {
                    awaitRequestHeaders(connection)
                    Thread.sleep(READ_SETTLE_MS)
                }
                stream.destroy()

                assertThat(stream.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                assertThat(stream.exitValue()).describedAs(output.readText()).isZero()
                assertThat(output.readText()).doesNotContain("metrics stream: published")
            }
        }
    }

    /** Waits for the stream to connect to the fake Mimir; a stream that never does fails with its own output, not a bare timeout. */
    private fun awaitMimirConnection(
        accepted: CompletableFuture<Socket>,
        stream: Process,
    ): Socket =
        try {
            accepted.get(STARTUP_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError(
                "the stream did not connect to Mimir within ${STARTUP_SECONDS}s (alive=${stream.isAlive}); output:\n${output.readText()}",
                e,
            )
        }

    /** Reads [connection] until the blank line that ends the HTTP request's headers. */
    private fun awaitRequestHeaders(connection: Socket) {
        connection.soTimeout = (STARTUP_SECONDS * 1000).toInt()
        val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        generateSequence { reader.readLine() }.first { it.isEmpty() }
    }

    @Test
    fun `a second SIGTERM while the stream is stopping exits at once with 143`() {
        // Redis replies pass through a relay the test controls.  Once a cycle has completed, the relay
        // holds requests, so the next cycle's ping waits on Redis; the first signal arrives during
        // that wait, and the stream is still stopping when the second one arrives.
        HoldingTcpRelay(redis.host, redis.getMappedPort(RedisTestSupport.PORT)).use { relay ->
            val stream = startStream("redis://127.0.0.1:${relay.port}/camera")
            awaitUntil("a completed cycle", { it.contains("metrics stream: published") }) { output.readText() }
            relay.hold = true
            relay.heldRequest.get(HELD_WAIT_SECONDS, TimeUnit.SECONDS)

            stream.destroy()
            assertThat(stream.waitFor(STILL_STOPPING_MS, TimeUnit.MILLISECONDS))
                .withFailMessage { "the first SIGTERM did not leave the stream stopping; exit ${stream.exitValue()}" }
                .isFalse()
            stream.destroy()

            assertThat(stream.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(stream.exitValue()).describedAs(output.readText()).isEqualTo(SIGTERM_EXIT)
        }
    }

    private companion object {
        const val STARTUP_ATTEMPTS = 60
        const val STARTUP_SECONDS = 60L
        const val EXIT_WAIT_SECONDS = 20L
        const val HELD_WAIT_SECONDS = 15L
        const val STILL_STOPPING_MS = 300L
        const val READ_SETTLE_MS = 200L
        const val SIGTERM_EXIT = 143
        val POLL: Duration = Duration.ofSeconds(1)
    }
}
