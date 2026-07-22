package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Integration-tier tests for [ProcessSocksProxyService] that genuinely require real socket I/O: the
 * reuse path (which connects to a real listening port to prove the tunnel is alive), the
 * port-fallback bind, and the zombie-port connect-refused. Every port here is OS-assigned via
 * `ServerSocket(0)` — no test binds a hardcoded port, so two of these running on a busy CI runner
 * can never collide on a fixed port (issue #750).
 *
 * The logic-only cases that used to live here (fail-fast on a dead ssh, which alias ssh dials,
 * orphaned-process cleanup, the verify loop, message construction) no longer spawn a real ssh — they
 * are driven through the injected [SshProcessLauncher] / mock [Process] seam and live in the fast
 * unit tier (`ProcessSocksProxyServiceUnitTest`, `ProcessSocksProxyMessageTest`). Where a fresh start
 * is needed here only to observe that a bad reuse candidate is rejected, this file injects a fake
 * launcher that returns an already-dead process so the fresh attempt fails fast and deterministically.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class ProcessSocksProxyServiceTest {
    private companion object {
        /** Tiny verify delay so the verify loop's retries do not sleep the production 500ms each. */
        val VERIFY_DELAY: Duration = Duration.ofMillis(1)

        /** Arbitrary PID returned by the fake dead process; never inspected for liveness here. */
        const val FAKE_PID = 4242L
    }

    @TempDir
    lateinit var tempDir: File

    private val json = Json { prettyPrint = true }
    private val testHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-east-1a",
        )

    @BeforeEach
    fun setUp() {
        File(tempDir, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        System.clearProperty("socksProxyHost")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("socksProxyHost")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    /**
     * Builds a service. [launcher] defaults to one that fails loudly if a fresh ssh spawn is
     * attempted, so the reuse-path tests are protected against accidentally launching; the tests
     * that deliberately trigger a (failing) fresh start pass a fake dead-process launcher.
     */
    private fun service(
        launcher: SshProcessLauncher =
            SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
    ) = ProcessSocksProxyService(
        Context.forCli(tempDir).copy(workingDirectory = tempDir),
        TunnelReachabilityProbe { _, _, _ -> false },
        verifyDelay = VERIFY_DELAY,
        processLauncher = launcher,
    )

    private fun writeStateFile(
        pid: Int,
        port: Int,
        controlIP: String = testHost.privateIp,
    ) {
        val sshConfigPath = File(tempDir, "sshConfig").absolutePath
        val stateFile =
            Socks5ProxyStateFile(
                pid = pid,
                port = port,
                controlHost = "control0",
                controlIP = controlIP,
                clusterName = tempDir.name,
                startTime = Instant.now().toString(),
                sshConfig = sshConfigPath,
            )
        File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).writeText(json.encodeToString(stateFile))
    }

    /** A fake process that has already exited with [exitCode] — models a dead ssh for a failing start. */
    private fun deadProcess(exitCode: Int): Process =
        mock {
            on { isAlive } doReturn false
            on { exitValue() } doReturn exitCode
            on { pid() } doReturn FAKE_PID
        }

    /** Reserves then releases an OS-assigned port, yielding a port that is (now) free — nothing listens. */
    private fun reserveFreePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `reuses proxy when state file has a live PID, matching IP, and a genuinely listening port`() {
        // Use this JVM's PID as a live PID, and actually bind the recorded port so it is genuinely
        // accepting connections — isValidProxy() checks that, not just the PID, so a merely-recorded
        // but unbound port would (correctly) be rejected as a zombie tunnel rather than reused.
        val livePid = ProcessHandle.current().pid().toInt()
        ServerSocket(0).use { socket ->
            val port = socket.localPort
            writeStateFile(pid = livePid, port = port)

            val state = service().ensureRunning(testHost)

            assertThat(state.localPort).isEqualTo(port)
            val loaded =
                json.decodeFromString<Socks5ProxyStateFile>(
                    File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).readText(),
                )
            assertThat(loaded.pid).isEqualTo(livePid)
        }
    }

    @Test
    fun `publishes the proxy port property but never the global socksProxyHost when reusing a valid proxy`() {
        val livePid = ProcessHandle.current().pid().toInt()
        ServerSocket(0).use { socket ->
            val port = socket.localPort
            writeStateFile(pid = livePid, port = port)

            service().ensureRunning(testHost)

            // The private port property is published for the cluster clients...
            assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("$port")
            // ...but the standard global socksProxyHost is NOT set, so java.net (and the AWS SDK) stay direct.
            assertThat(System.getProperty("socksProxyHost")).isNull()
        }
    }

    @Test
    fun `selectPort falls back to an OS-assigned port when the preferred port is bound`() {
        ServerSocket(0).use { occupied ->
            val occupiedPort = occupied.localPort

            // With the preferred port already bound, selectPort must catch the BindException and
            // return a different, OS-assigned port rather than the occupied one.
            val selected = service().selectPort(preferred = occupiedPort)

            assertThat(selected).isNotEqualTo(occupiedPort)
            assertThat(selected).isGreaterThan(0)
        }
    }

    @Test
    fun `a live PID whose recorded port is not listening is not reused`() {
        val livePid = ProcessHandle.current().pid().toInt()
        val deadPort = reserveFreePort() // recorded but nothing is listening on it

        writeStateFile(pid = livePid, port = deadPort)

        // isValidProxy() rejects the zombie (port not accepting), so a fresh start is attempted; the
        // fake launcher makes that start fail fast so we can observe the zombie was rejected, not reused.
        assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        // The zombie's port must never be republished — it was rejected as invalid, and the fresh
        // attempt also failed.
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a superseded zombie tunnel process is killed when a replacement is started`() {
        // A real, killable stand-in for a zombie ssh tunnel: the process is alive, but its recorded
        // `-D` port is not listening, so isValidProxy() rejects it and a replacement is started.
        // Before the fix, startNewProxy() overwrote the state file with the new PID and this one was
        // never killed — it leaked and survived `down` (issue #741).
        val zombie = ProcessBuilder("sleep", "60").start()
        try {
            val deadPort = reserveFreePort() // recorded but nothing is listening on it
            writeStateFile(pid = zombie.pid().toInt(), port = deadPort)

            // The fresh start fails fast (fake dead launcher); we only care that the stale zombie
            // was reaped on the way through.
            assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
                .isInstanceOf(IllegalStateException::class.java)

            assertThat(zombie.waitFor(5, TimeUnit.SECONDS))
                .withFailMessage("the superseded zombie tunnel process should have been killed")
                .isTrue()
        } finally {
            zombie.destroyForcibly()
        }
    }

    @Test
    fun `an in-memory port that dies between calls is re-validated, not trusted, on the next call`() {
        // Populate in-memory state via a genuine reuse (real listening socket), then close the
        // listener so the recorded port stops accepting while the recorded PID (this JVM) stays
        // alive — the "process alive, tunnel dead" case the in-memory fast path must not trust.
        val livePid = ProcessHandle.current().pid().toInt()
        val svc = service(launcher = { _, _ -> deadProcess(exitCode = 255) })
        ServerSocket(0).use { socket ->
            val port = socket.localPort
            writeStateFile(pid = livePid, port = port)
            val first = svc.ensureRunning(testHost)
            assertThat(first.localPort).isEqualTo(port)
        }

        // The socket is now closed. A second call on the SAME instance must not shortcut through the
        // in-memory cache — it must re-validate, find nothing reusable, and attempt a fresh start
        // (which fails fast via the fake launcher).
        assertThatThrownBy { svc.ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
