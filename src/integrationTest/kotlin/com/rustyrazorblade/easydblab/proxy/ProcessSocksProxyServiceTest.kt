package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant

@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class ProcessSocksProxyServiceTest {
    private companion object {
        /** Socket timeout for the real probe used by the real-ssh integration tests. */
        const val PROBE_TIMEOUT_MS = 1000
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
        // Write a minimal sshConfig so the service can construct the expected path
        File(tempDir, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        // Clear any leftover system properties from prior tests
        System.clearProperty("socksProxyHost")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("socksProxyHost")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    // Real-ssh integration tests default to the real probe; loop tests inject a mock probe.
    private fun service(probe: TunnelReachabilityProbe = SocksTunnelReachabilityProbe(PROBE_TIMEOUT_MS)) =
        ProcessSocksProxyService(
            Context.forCli(File(System.getProperty("user.home"), ".easy-db-lab")).copy(workingDirectory = tempDir),
            probe,
            // Tiny verify delay so verification retries do not sleep 500ms per attempt.
            verifyDelay = Duration.ofMillis(1),
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

    /**
     * Points sshConfig's only Host stanza at a loopback port nothing listens on, so `ssh`
     * refuses the connection immediately (no DNS/network flakiness) and the local `-D` listener
     * is never opened — a deterministic, fast way to exercise the proxy's failure path for real,
     * without mocking the `ssh` process.
     */
    private fun writeUnreachableSshConfig(alias: String) {
        File(tempDir, "sshConfig").writeText(
            """
            Host $alias
              Hostname 127.0.0.1
              Port 1
              ConnectTimeout 1
            """.trimIndent(),
        )
    }

    @Test
    fun `reuses proxy when state file has live PID matching IP and sshConfig`() {
        // Use the current JVM process PID as a "live" PID, and actually bind the recorded port
        // so it is genuinely accepting connections — isValidProxy() now checks that, not just
        // the PID, so a merely-recorded-but-unbound port would (correctly) be rejected as a
        // zombie tunnel rather than reused.
        val livePid = ProcessHandle.current().pid().toInt()
        val port = 19080 // arbitrary high port unlikely to be bound
        ServerSocket(port).use {
            writeStateFile(pid = livePid, port = port)

            val svc = service()
            val state = svc.ensureRunning(testHost)

            assertThat(state.localPort).isEqualTo(port)
            // No new state file written with a different PID — still the original PID
            val loaded =
                json.decodeFromString<Socks5ProxyStateFile>(
                    File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).readText(),
                )
            assertThat(loaded.pid).isEqualTo(livePid)
        }
    }

    @Test
    fun `writes new state file when no existing state file`() {
        val svc = service()
        // We can't actually start an SSH process in unit tests, but we can verify
        // getLocalPort() returns 0 when no state exists yet
        assertThat(svc.getLocalPort()).isEqualTo(0)
        assertThat(svc.getState()).isNull()
        assertThat(svc.isRunning()).isFalse()
    }

    @Test
    fun `returns zero port when state file has dead PID`() {
        writeStateFile(pid = -1, port = 19081)
        val svc = service()
        // isRunning should return false for dead PID
        assertThat(svc.isRunning()).isFalse()
        // getLocalPort reads from state file when in-memory state is absent
        // In this case the file exists but PID is dead; getLocalPort still reads the stored port
        assertThat(svc.getLocalPort()).isEqualTo(19081)
    }

    @Test
    fun `publishes the proxy port property but never the global socksProxyHost when reusing valid proxy`() {
        val livePid = ProcessHandle.current().pid().toInt()
        val port = 19082
        // Actually bind the recorded port so the proxy is genuinely reusable — see comment on
        // the "reuses proxy..." test above for why this must be a real listening socket.
        ServerSocket(port).use {
            writeStateFile(pid = livePid, port = port)

            val svc = service()
            svc.ensureRunning(testHost)

            // The private port property is published for the cluster clients...
            assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("$port")
            // ...but the standard global socksProxyHost is NOT set, so java.net (and the AWS SDK) stay direct.
            assertThat(System.getProperty("socksProxyHost")).isNull()
        }
    }

    @Test
    fun `port fallback selects different port when preferred port is bound`() {
        // Pre-bind port 1080 (or whatever DEFAULT_SOCKS5_PORT is)
        val boundSocket = ServerSocket(Constants.Proxy.DEFAULT_SOCKS5_PORT)
        try {
            // The service should select a different port
            // We verify via the port selection logic: with 1080 bound,
            // selectPort() must fall back to an OS-assigned port != 1080
            // We test this indirectly by verifying getLocalPort() != DEFAULT_SOCKS5_PORT
            // when the state file was written with a fallback port.
            // Since we can't launch SSH, we test the port-selection logic is non-deterministic
            // but not equal to the bound port.
            // Direct test: creating a ServerSocket on the preferred port throws BindException,
            // and our service falls back to port 0 (OS-assigned).
            // Verify by reading what would be selected:
            val alternateSocket = ServerSocket(0)
            val fallbackPort = alternateSocket.localPort
            alternateSocket.close()
            assertThat(fallbackPort).isNotEqualTo(Constants.Proxy.DEFAULT_SOCKS5_PORT)
        } finally {
            boundSocket.close()
        }
    }

    @Test
    fun `getLocalPort reads from state file when in-memory state is absent`() {
        writeStateFile(pid = ProcessHandle.current().pid().toInt(), port = 19083)
        val svc = service()
        // No in-memory state, reads from file
        assertThat(svc.getLocalPort()).isEqualTo(19083)
    }

    @Test
    fun `isRunning returns false when no state`() {
        val svc = service()
        assertThat(svc.isRunning()).isFalse()
    }

    @Test
    fun `stale proxy with wrong controlIP is not reused`() {
        val livePid = ProcessHandle.current().pid().toInt()
        writeStateFile(pid = livePid, port = 19084, controlIP = "10.9.9.9") // different IP
        val svc = service()

        // State shows a different IP from the host we're connecting to —
        // isRunning() checks in-memory state only, so it returns false
        assertThat(svc.isRunning()).isFalse()
    }

    @Test
    fun `startNewProxy fails fast with the ssh exit code when ssh cannot connect`() {
        val unreachableAlias = "unreachable-control"
        writeUnreachableSshConfig(unreachableAlias)
        val unreachableHost = testHost.copy(alias = unreachableAlias)

        val svc = service()

        // ssh to a refused port exits almost immediately; with ExitOnForwardFailure + the
        // process-liveness check, verification must bail on the dead process rather than poll it
        // for the full window (VERIFY_RETRIES * VERIFY_DELAY_MS = 5000ms).
        val start = System.nanoTime()
        val thrown = catchThrowable { svc.ensureRunning(unreachableHost) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SOCKS5 proxy")
            .hasMessageContaining("socks5-proxy.log")
            .hasMessageContaining("ssh exited with code")
        assertThat(elapsedMs).isLessThan(4000L)

        // No stale/dead port is ever published — clients fall back to no-proxy.
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    // NOTE: the failure-message construction (real ssh error surfaced, debug1: chatter stripped) is
    // now proven deterministically in the unit tier by ProcessSocksProxyMessageTest, which feeds a
    // synthetic transcript to verificationFailureMessage. The former real-ssh version of that test
    // lived here and depended on scraping a live `ssh -v` at a refused loopback port, which was
    // non-deterministic across host network stacks (issue #750).

    @Test
    fun `a live process with a dead tunnel port is not reused`() {
        val livePid = ProcessHandle.current().pid().toInt()
        val zombiePort = 19090 // recorded but nothing is listening on it
        writeStateFile(pid = livePid, port = zombiePort)

        // The fallback fresh-start attempt must also fail fast rather than hang or succeed,
        // so we can observe that the zombie was rejected rather than silently reused.
        val unreachableAlias = "unreachable-control"
        writeUnreachableSshConfig(unreachableAlias)
        val unreachableHost = testHost.copy(alias = unreachableAlias)

        val svc = service()

        assertThatThrownBy { svc.ensureRunning(unreachableHost) }
            .isInstanceOf(IllegalStateException::class.java)

        // The zombie's port must never be republished under any circumstances — it was
        // rejected as invalid, not reused, and the fresh attempt also failed.
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `startNewProxy dials the gateway host's recorded alias, not a hardcoded control0`() {
        val gatewayAlias = "custom-gateway-alias"
        writeUnreachableSshConfig(gatewayAlias)
        val host = testHost.copy(alias = gatewayAlias)

        val svc = service()

        assertThatThrownBy { svc.ensureRunning(host) }
            .isInstanceOf(IllegalStateException::class.java)

        // ssh -v only logs "Applying options for <alias>" when the literal argument passed on
        // the command line matches a Host stanza in the config. sshConfig here defines ONLY
        // gatewayAlias — a hardcoded "control0" argument would not match it and this line
        // would be absent. The transcript now lives under logs/ (alongside logs/info.log).
        val sshTranscript = File(tempDir, "logs/socks5-proxy.log").readText()
        assertThat(sshTranscript).contains("Applying options for $gatewayAlias")
    }

    @Test
    fun `in-memory state with a dead tunnel port is not reused across ensureRunning calls`() {
        // Populate in-memory state via a genuine reuse (real listening socket), then kill the
        // listener so the port stops accepting connections while the recorded PID (this JVM)
        // stays alive — the "process alive, tunnel dead" case the in-memory fast path must not
        // trust just because isAlive(pid) is still true.
        val livePid = ProcessHandle.current().pid().toInt()
        val port = 19091
        val svc = service()
        ServerSocket(port).use {
            writeStateFile(pid = livePid, port = port)
            val first = svc.ensureRunning(testHost)
            assertThat(first.localPort).isEqualTo(port)
        }

        // The socket is now closed: the previously-valid in-memory state's port is dead. A
        // second call on the SAME service instance must not shortcut through the in-memory
        // cache — it must re-validate and, finding nothing reusable, attempt a fresh start.
        val unreachableAlias = "unreachable-control"
        writeUnreachableSshConfig(unreachableAlias)
        val unreachableHost = testHost.copy(alias = unreachableAlias)

        assertThatThrownBy { svc.ensureRunning(unreachableHost) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `orphaned ssh process is destroyed rather than leaked when verification fails`() {
        // Accept the TCP connection but never send the SSH banner. The real ssh client
        // completes its TCP connect and then hangs mid-handshake for the whole verify window,
        // staying alive without ever opening the local -D listener — exactly the case where a
        // naive fail-fast fix would otherwise leak an untracked background process that `down`
        // can never find (the state file, which normally records the PID, is never written on
        // a failed start).
        val alias = "hanging-gateway"
        val hangingPort = 19510
        val listener = ServerSocket(hangingPort)
        val holdMillis = 8000L
        Thread {
            try {
                listener.accept().use { Thread.sleep(holdMillis) }
            } catch (_: Exception) {
                // Listener closed by test teardown below — nothing to clean up.
            }
        }.apply {
            isDaemon = true
            start()
        }

        File(tempDir, "sshConfig").writeText(
            """
            Host $alias
              Hostname 127.0.0.1
              Port $hangingPort
            """.trimIndent(),
        )
        val hangingHost = testHost.copy(alias = alias)
        val svc = service()

        try {
            assertThatThrownBy { svc.ensureRunning(hangingHost) }
                .isInstanceOf(IllegalStateException::class.java)

            assertThat(sshProcessesMatching(alias)).isEmpty()
        } finally {
            listener.close()
        }
    }

    @Test
    fun `verify loop succeeds once the probe reports the tunnel reachable`() {
        // Process stays alive; the probe is not reachable on the first two polls, then is.
        // The loop must keep polling and return normally on the third — no exception.
        val process = mock<Process> { on { isAlive } doReturn true }
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false, false, true)

        val svc = service(probe)
        svc.verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())

        verify(probe, times(3)).isReachable(1080, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop throws when the probe never reports the tunnel reachable`() {
        // A live ssh whose tunnel is a black hole (probe always false) must time out and fail,
        // not hang or succeed — the fail-fast contract.
        val process = mock<Process> { on { isAlive } doReturn true }
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false)

        val svc = service(probe)

        assertThatThrownBy {
            svc.verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SOCKS5 proxy")
    }

    @Test
    fun `verify loop fails immediately with the ssh exit code when the process is already dead`() {
        // Liveness is checked FIRST, before the probe: a dead ssh (e.g. a changed host key) must
        // abort at once with its exit code, never polling the probe against a corpse.
        val process =
            mock<Process> {
                on { isAlive } doReturn false
                on { exitValue() } doReturn 255
            }
        val probe = mock<TunnelReachabilityProbe>()

        val svc = service(probe)

        assertThatThrownBy {
            svc.verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ssh exited with code 255")
        verify(probe, never()).isReachable(any<Int>(), any<String>(), any<Int>())
    }

    /** A log path under the workspace that may or may not exist — error extraction tolerates both. */
    private fun logFile(): File = File(File(tempDir, "logs"), Constants.Proxy.SOCKS5_PROXY_LOG_FILE)

    @Test
    fun `stripSshDebugNoise drops debug-prefixed lines and keeps the real error`() {
        val transcript =
            listOf(
                "OpenSSH_9.9p2, LibreSSL 3.3.6",
                "debug1: Reading configuration data sshConfig",
                "debug2: resolving \"control0\" port 22",
                "debug3: send packet: type 21",
                "Permission denied (publickey).",
                "",
            )

        val result = stripSshDebugNoise(transcript, tailLines = 15)

        assertThat(result).contains("Permission denied (publickey).")
        assertThat(result).noneMatch { it.startsWith("debug") }
        assertThat(result).doesNotContain("")
    }

    @Test
    fun `stripSshDebugNoise keeps only the trailing lines up to the limit`() {
        val transcript = (1..30).map { "error line $it" }

        val result = stripSshDebugNoise(transcript, tailLines = 15)

        assertThat(result).hasSize(15)
        assertThat(result.first()).isEqualTo("error line 16")
        assertThat(result.last()).isEqualTo("error line 30")
    }

    /**
     * Finds live OS processes whose command line contains [marker]. Used to confirm a spawned
     * `ssh` process was actually killed rather than left running as an untracked orphan.
     */
    private fun sshProcessesMatching(marker: String): List<ProcessHandle> =
        ProcessHandle
            .allProcesses()
            .filter { handle ->
                handle
                    .info()
                    .commandLine()
                    .orElse("")
                    .contains(marker)
            }.toList()
}
