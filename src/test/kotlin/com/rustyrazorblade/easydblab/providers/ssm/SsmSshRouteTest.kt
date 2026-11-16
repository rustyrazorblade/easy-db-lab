package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.SshEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Tests for [SsmSshRoute] against a stub `aws` script, so the real port-forward handling
 * (readiness detection, failure surfacing, reuse, teardown) runs without AWS or the Session
 * Manager plugin.
 *
 * The stub records each invocation's PID and arguments next to itself and behaves according to a
 * `mode` file: `ready` prints the plugin's real readiness output then idles, `fail` prints a
 * Session Manager error and exits 254, `silent` idles without ever reporting ready, and
 * `session-silent` reports its session ID (`lab-session-<pid>`) but never that it is ready. The `child-`
 * variants of `ready` and `silent` first start a background child, standing in for the
 * `session-manager-plugin` process the AWS CLI spawns, and record its PID in `childpids`.
 *
 * Every process the route starts is recorded, and the route is not handed it until the stub has
 * touched `started-<pid>`. The route's ready timeout only starts once it has the process, so a
 * short timeout cannot kill the stub before it has recorded what the test asserts on, however
 * loaded the machine is.
 */
internal class SsmSshRouteTest {
    @TempDir
    lateinit var stubDir: File

    private val routes = mutableListOf<SsmSshRoute>()
    private val nextPort = AtomicInteger(41000)
    private val started: MutableList<Process> = CopyOnWriteArrayList()

    /** Every session ID the route asked AWS to terminate, in order. */
    private val terminated: MutableList<String> = CopyOnWriteArrayList()
    private val warmUps = AtomicInteger(0)
    private val recordingTerminator =
        object : SsmSessionTerminator {
            override fun terminate(sessionId: String) {
                terminated.add(sessionId)
            }

            override fun warmUp() {
                warmUps.incrementAndGet()
            }
        }

    /** Counted down each time a started stub is handed to the route, which then waits for it to be ready. */
    private val handedOver = CountDownLatch(1)

    @BeforeEach
    fun writeStub() {
        File(stubDir, "aws").apply {
            writeText(
                """
                |#!/bin/sh
                |dir=${'$'}(dirname "${'$'}0")
                |echo "${'$'}${'$'}" >> "${'$'}dir/pids"
                |echo "${'$'}@" >> "${'$'}dir/args"
                |mode=${'$'}(cat "${'$'}dir/mode")
                |case "${'$'}mode" in
                |  child-*)
                |    sleep 300 &
                |    echo "${'$'}!" >> "${'$'}dir/childpids" ;;
                |  session-silent)
                |    # Printed before the started marker, so it is in the pipe before the route waits.
                |    echo "Starting session with SessionId: lab-session-${'$'}${'$'}" ;;
                |esac
                |touch "${'$'}dir/started-${'$'}${'$'}"
                |case "${'$'}mode" in
                |  ready|child-ready)
                |    echo "Starting session with SessionId: lab-session-${'$'}${'$'}"
                |    echo "Port opened for sessionId lab-session-${'$'}${'$'}."
                |    echo "Waiting for connections..."
                |    exec sleep 300 ;;
                |  session-silent)
                |    exec sleep 300 ;;
                |  fail)
                |    echo "An error occurred (TargetNotConnected) when calling the StartSession operation: i-test is not connected."
                |    exit 254 ;;
                |  silent|child-silent)
                |    exec sleep 300 ;;
                |esac
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        mode("ready")
    }

    @AfterEach
    fun closeRoutes() {
        routes.forEach { it.close() }
    }

    @Test
    fun `dials the loopback port once the plugin reports it is listening`() {
        val endpoint = route().endpoint(host("i-0abc"))

        assertThat(endpoint).isEqualTo(SshEndpoint("127.0.0.1", 41000))
        assertThat(File(stubDir, "args").readText()).contains("--target i-0abc", "portNumber=22,localPortNumber=41000")
    }

    @Test
    fun `reuses the live forward for the same instance`() {
        val route = route()

        val first = route.endpoint(host("i-0abc"))
        val second = route.endpoint(host("i-0abc"))

        assertThat(second).isEqualTo(first)
        assertThat(pids()).hasSize(1)
    }

    @Test
    fun `starts a separate forward for each instance`() {
        val route = route()

        val first = route.endpoint(host("i-0abc"))
        val second = route.endpoint(host("i-0def"))

        assertThat(second.port).isNotEqualTo(first.port)
        assertThat(pids()).hasSize(2)
    }

    @Test
    fun `replaces a forward whose process has died`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        ProcessHandle
            .of(pids().single())
            .get()
            .also { it.destroy() }
            .onExit()
            .get(5, TimeUnit.SECONDS)

        val endpoint = route.endpoint(host("i-0abc"))

        assertThat(endpoint.port).isEqualTo(41001)
        assertThat(pids()).hasSize(2)
    }

    @Test
    fun `a session that exits before it is ready fails with the plugin output`() {
        mode("fail")

        assertThatThrownBy { route().endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)
            .hasMessageContaining("exit code 254")
            .hasMessageContaining("TargetNotConnected")
    }

    @Test
    fun `a session that never reports ready is killed after the timeout`() {
        mode("silent")

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500)).endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)
            .hasMessageContaining("did not become ready")
        assertThat(started.single().isAlive).isFalse()
    }

    @Test
    fun `the ready-timeout kill also stops the session's child process`() {
        mode("child-silent")

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500)).endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)

        assertThat(childPids()).hasSize(1).noneMatch { isAlive(it) }
    }

    @Test
    fun `close stops each forward's child process too`() {
        mode("child-ready")
        val route = route()
        route.endpoint(host("i-0abc"))
        assertThat(childPids()).hasSize(1).allMatch { isAlive(it) }

        route.close()

        assertThat(childPids()).noneMatch { isAlive(it) }
    }

    @Test
    fun `an interrupted wait stops the starting process and keeps the interrupt`() {
        mode("silent")
        val route = route(readyTimeout = Duration.ofSeconds(60))
        val failure = AtomicReference<Throwable>()
        val stillInterrupted = AtomicBoolean(false)
        val waiter =
            thread {
                try {
                    route.endpoint(host("i-0abc"))
                } catch (e: InterruptedException) {
                    failure.set(e)
                    stillInterrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        assertThat(handedOver.await(STUB_START_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        waiter.interrupt()
        waiter.join(TimeUnit.SECONDS.toMillis(STUB_START_LIMIT_SECONDS))

        assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(stillInterrupted.get()).isTrue()
        assertThat(started.single().isAlive).isFalse()
    }

    @Test
    fun `concurrent requests for one instance share a single forwarding process`() {
        val route = route()
        val threads = 8
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val endpoints =
                (1..threads)
                    .map {
                        pool.submit(
                            Callable {
                                go.await()
                                route.endpoint(host("i-0abc"))
                            },
                        )
                    }.also { go.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }

            assertThat(endpoints).containsOnly(SshEndpoint("127.0.0.1", 41000))
            assertThat(started).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failed start is not cached, so the next request tries again`() {
        val route = route()
        mode("fail")
        assertThatThrownBy { route.endpoint(host("i-0abc")) }.isInstanceOf(SsmForwardNotReadyException::class.java)

        mode("ready")
        val endpoint = route.endpoint(host("i-0abc"))

        assertThat(endpoint.port).isEqualTo(41001)
    }

    @Test
    fun `close terminates every forwarding process`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        route.endpoint(host("i-0def"))

        route.close()

        assertThat(pids()).hasSize(2).noneMatch { isAlive(it) }
    }

    // =========================================================================
    // Ending the AWS-side session. Killing the local processes alone leaves the session
    // "Connected" in Session Manager until its 20-minute idle timeout.
    // =========================================================================

    /**
     * A forward can report ready, accept the TCP connection, and then carry no SSH data at all.
     * Its process stays alive, so only the SSH layer's report that the endpoint failed can retire
     * it; the next request must get a new forward, and the stuck one's session must end.
     */
    @Test
    fun `an invalidated forward is stopped, its session ended, and the next request starts a new one`() {
        val route = route()
        val first = route.endpoint(host("i-0abc"))
        val stuck = started.single()

        route.invalidate(host("i-0abc"))
        val second = route.endpoint(host("i-0abc"))

        assertThat(stuck.isAlive).isFalse()
        assertThat(terminated).containsExactly("lab-session-${stuck.pid()}")
        assertThat(second.port).isNotEqualTo(first.port)
        assertThat(started).hasSize(2)
    }

    @Test
    fun `invalidating a host with no forward does nothing`() {
        val route = route()

        route.invalidate(host("i-0abc"))

        assertThat(started).isEmpty()
        assertThat(terminated).isEmpty()
    }

    /** The SSM client and credentials warm up while the first forward gets ready, not at exit. */
    @Test
    fun `the session terminator warms up when a forward starts, not before`() {
        val route = route()
        assertThat(warmUps.get()).isZero()

        route.endpoint(host("i-0abc"))

        assertThat(warmUps.get()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `close ends each forward's session in Session Manager`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        route.endpoint(host("i-0def"))

        route.close()

        assertThat(terminated).containsExactlyInAnyOrderElementsOf(sessionIds())
        assertThat(terminated).hasSize(2)
    }

    @Test
    fun `replacing a dead forward ends the dead one's session`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        val first = sessionIds().single()
        started
            .single()
            .also { it.destroy() }
            .onExit()
            .get(5, TimeUnit.SECONDS)

        route.endpoint(host("i-0abc"))

        assertThat(terminated).containsExactly(first)
    }

    @Test
    fun `the ready-timeout kill ends the session it had opened`() {
        mode("session-silent")

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500)).endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)

        assertThat(terminated).containsExactlyElementsOf(sessionIds())
        assertThat(terminated).hasSize(1)
    }

    @Test
    fun `an interrupted start ends the session it had opened`() {
        mode("session-silent")
        val route = route(readyTimeout = Duration.ofSeconds(60))
        val waiter = thread { runCatching { route.endpoint(host("i-0abc")) } }
        assertThat(handedOver.await(STUB_START_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        waiter.interrupt()
        waiter.join(TimeUnit.SECONDS.toMillis(STUB_START_LIMIT_SECONDS))

        assertThat(terminated).containsExactlyElementsOf(sessionIds())
        assertThat(terminated).hasSize(1)
    }

    @Test
    fun `a forward that never reported a session ID ends no session`() {
        mode("silent")

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500)).endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)

        assertThat(terminated).isEmpty()
    }

    /** A TerminateSession that fails must not replace the error the caller needs to see. */
    @Test
    fun `a failed TerminateSession does not hide the readiness failure`() {
        mode("session-silent")
        val failing = SsmSessionTerminator { error("AccessDeniedException: not authorized to perform ssm:TerminateSession") }

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500), sessionTerminator = failing).endpoint(host("i-0abc")) }
            .isInstanceOf(SsmForwardNotReadyException::class.java)
            .hasMessageContaining("did not become ready")
    }

    /** On a slow network a TerminateSession can take several seconds; within the 20s budget it must complete. */
    @Test
    fun `a TerminateSession slower than 5s but within the budget still ends the session`() {
        val slow =
            SsmSessionTerminator {
                Thread.sleep(SLOW_TERMINATE_MS)
                terminated.add(it)
            }
        val route = route(sessionTerminator = slow, terminateTimeout = Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS))
        route.endpoint(host("i-0abc"))

        route.close()

        assertThat(terminated).containsExactlyElementsOf(sessionIds())
    }

    /** close runs from the JVM shutdown hook, so an AWS call that never answers must not hold it. */
    @Test
    fun `a TerminateSession that never answers does not block close past its timeout`() {
        val hung = SsmSessionTerminator { Thread.sleep(TimeUnit.MINUTES.toMillis(10)) }
        val route = route(sessionTerminator = hung, terminateTimeout = Duration.ofMillis(300))
        route.endpoint(host("i-0abc"))

        val startedAt = System.nanoTime()
        assertThatCode { route.close() }.doesNotThrowAnyException()

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(STUB_START_LIMIT_SECONDS))
        assertThat(started.single().isAlive).isFalse()
    }

    /** The session IDs the stubs printed, one per started stub that printed one. */
    private fun sessionIds(): List<String> = pids().map { "lab-session-$it" }

    @Test
    fun `a host with no instance ID is refused instead of being dialed directly`() {
        val route = route()

        assertThatThrownBy { route.endpoint(host("")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db0")
        assertThatThrownBy { route.proxyCommand(host("")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db0")
        assertThat(pids()).isEmpty()
    }

    /**
     * A route over the stub. The default ready timeout only bounds a test that would otherwise
     * hang: every test that does not exercise the timeout finishes when the stub reports ready or
     * exits, so it is long enough never to fire on a loaded machine.
     */
    private fun route(
        readyTimeout: Duration = Duration.ofSeconds(STUB_START_LIMIT_SECONDS),
        sessionTerminator: SsmSessionTerminator = recordingTerminator,
        terminateTimeout: Duration = Duration.ofSeconds(STUB_START_LIMIT_SECONDS),
    ): SsmSshRoute {
        val commands =
            SsmSessionCommandBuilder(
                "us-west-2",
                { SsmCliCredentials.NamedProfile("lab") },
                awsExecutable = File(stubDir, "aws").absolutePath,
            )
        return SsmSshRoute(
            commands,
            sshPort = 22,
            sessionTerminator = sessionTerminator,
            terminateTimeout = terminateTimeout,
            readyTimeout = readyTimeout,
            freeLocalPort = { nextPort.getAndIncrement() },
            startProcess = ::startRecorded,
        ).also { routes.add(it) }
    }

    /** Starts the stub, records it, and returns once it has recorded itself or exited. */
    private fun startRecorded(builder: ProcessBuilder): Process {
        val process = builder.start().also { started.add(it) }
        val marker = File(stubDir, "started-${process.pid()}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUB_START_LIMIT_SECONDS)
        while (!marker.exists() && process.isAlive && System.nanoTime() < deadline) {
            Thread.sleep(STUB_POLL_MS)
        }
        check(marker.exists() || !process.isAlive) { "stub ${process.pid()} did not start within ${STUB_START_LIMIT_SECONDS}s" }
        handedOver.countDown()
        return process
    }

    private fun host(instanceId: String) =
        Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "a", instanceId = instanceId)

    private fun mode(value: String) = File(stubDir, "mode").writeText(value)

    private fun pids(): List<Long> = pidsIn("pids")

    private fun childPids(): List<Long> = pidsIn("childpids")

    private fun pidsIn(fileName: String): List<Long> =
        File(stubDir, fileName)
            .takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?.map { it.trim().toLong() }
            .orEmpty()

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private companion object {
        const val STUB_START_LIMIT_SECONDS = 30L
        const val STUB_POLL_MS = 10L
        const val SLOW_TERMINATE_MS = 6_000L
    }
}
