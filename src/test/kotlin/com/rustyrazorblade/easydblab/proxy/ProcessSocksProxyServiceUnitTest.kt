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
import java.time.Duration
import java.time.Instant

/**
 * Fast unit-tier tests for [ProcessSocksProxyService]: the state-file bookkeeping, the fail-fast
 * verify loop, the ssh command construction, and orphaned-process cleanup — everything that can be
 * proven with an injected fake [SshProcessLauncher] / mock [Process] and a mock
 * [TunnelReachabilityProbe], with no real ssh and no real socket bind/connect.
 *
 * The former versions of several of these tests lived in the integration tier and spawned a real
 * `ssh` against a fixed loopback port, which was non-deterministic across host network stacks
 * (issue #750). Driving the service's decisions through the injected process seam proves the same
 * behavioral contracts with no timing dependence. The tests that genuinely need a real listening
 * socket (proxy reuse, the port-fallback bind, the zombie-port connect) remain in the integration
 * tier in `ProcessSocksProxyServiceTest`.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class ProcessSocksProxyServiceUnitTest {
    private companion object {
        /** Tiny verify delay so the verify loop's retries do not sleep the production 500ms each. */
        val VERIFY_DELAY: Duration = Duration.ofMillis(1)

        /** Arbitrary PID returned by fake processes; never inspected for liveness by these tests. */
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
     * Builds a service with an injected [probe] and [launcher]. The default launcher fails loudly if
     * called, so tests that must not reach a fresh ssh spawn are protected against accidentally
     * doing so; tests that exercise a spawn pass an explicit fake.
     */
    private fun service(
        probe: TunnelReachabilityProbe = TunnelReachabilityProbe { _, _, _ -> false },
        launcher: SshProcessLauncher =
            SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
    ) = ProcessSocksProxyService(
        Context.forCli(tempDir).copy(workingDirectory = tempDir),
        probe,
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

    /** A log path under the workspace that may or may not exist — error extraction tolerates both. */
    private fun logFile(): File = File(File(tempDir, "logs"), Constants.Proxy.SOCKS5_PROXY_LOG_FILE)

    /** A fake process that has already exited with [exitCode] — models a dead ssh. */
    private fun deadProcess(exitCode: Int): Process =
        mock {
            on { isAlive } doReturn false
            on { exitValue() } doReturn exitCode
            on { pid() } doReturn FAKE_PID
        }

    /** A fake process that stays alive — models an ssh that hangs mid-handshake. */
    private fun aliveProcess(): Process =
        mock {
            on { isAlive } doReturn true
            on { pid() } doReturn FAKE_PID
        }

    @Test
    fun `getLocalPort is zero and state absent when no state file exists`() {
        val svc = service()
        assertThat(svc.getLocalPort()).isEqualTo(0)
        assertThat(svc.getState()).isNull()
        assertThat(svc.isRunning()).isFalse()
    }

    @Test
    fun `getLocalPort reads the recorded port from the state file even when the PID is dead`() {
        writeStateFile(pid = -1, port = 25123)
        val svc = service()
        // A dead PID means the proxy is not running, but the recorded port is still surfaced so
        // callers can report what the last proxy used.
        assertThat(svc.isRunning()).isFalse()
        assertThat(svc.getLocalPort()).isEqualTo(25123)
    }

    @Test
    fun `getLocalPort reads from the state file when no in-memory state is present`() {
        writeStateFile(pid = ProcessHandle.current().pid().toInt(), port = 25456)
        val svc = service()
        assertThat(svc.getLocalPort()).isEqualTo(25456)
    }

    @Test
    fun `isRunning is false when there is no in-memory state`() {
        assertThat(service().isRunning()).isFalse()
    }

    @Test
    fun `stale proxy state for a different control IP is not treated as running`() {
        writeStateFile(pid = ProcessHandle.current().pid().toInt(), port = 25789, controlIP = "10.9.9.9")
        // isRunning() consults only in-memory state (never populated here), so a state file for a
        // different control IP must not make the service report itself running.
        assertThat(service().isRunning()).isFalse()
    }

    @Test
    fun `buildSshCommand dials the recorded alias with fail-fast forwarding options`() {
        val command = service().buildSshCommand(port = 1080, sshConfigPath = "/work/sshConfig", alias = "gw-alias")

        // The alias must be the final ssh argument (the host to connect to), and the command must
        // carry the dynamic forward and the ExitOnForwardFailure option that lets a dead ssh be
        // detected fast. A hardcoded control0 would show up here instead of the passed alias.
        assertThat(command.last()).isEqualTo("gw-alias")
        assertThat(command).doesNotContain("control0")
        assertThat(command).containsSequence("-D", "1080")
        assertThat(command).containsSequence("-F", "/work/sshConfig")
        assertThat(command).contains("ExitOnForwardFailure=yes")
    }

    @Test
    fun `startNewProxy launches ssh with the gateway's recorded alias, not a hardcoded control0`() {
        val gatewayAlias = "custom-gateway-alias"
        val host = testHost.copy(alias = gatewayAlias)
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, _ ->
                launched.add(command)
                deadProcess(exitCode = 255)
            }

        // The launch fails verification (dead process), but we only care which command was launched.
        assertThatThrownBy { service(launcher = launcher).ensureRunning(host) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(launched).hasSize(1)
        assertThat(launched.single().last()).isEqualTo(gatewayAlias)
        assertThat(launched.single()).doesNotContain("control0")
    }

    @Test
    fun `startNewProxy fails fast with the ssh exit code and never publishes a port`() {
        val launcher = SshProcessLauncher { _, _ -> deadProcess(exitCode = 255) }

        val thrown = catchThrowable { service(launcher = launcher).ensureRunning(testHost) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SOCKS5 proxy")
            .hasMessageContaining(Constants.Proxy.SOCKS5_PROXY_LOG_FILE)
            .hasMessageContaining("ssh exited with code 255")
        // A failed start must never advertise a dead port — clients fall back to no-proxy.
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `orphaned ssh process is destroyed rather than leaked when verification fails`() {
        // The ssh stays alive but its tunnel is a black hole (probe never reachable): the process is
        // never recorded in a state file, so if the service did not destroy it here it would become
        // an untracked orphan that `down` could never find. Verifying destroyForcibly() proves the
        // cleanup happens — without a real process or a fragile OS process-table scan.
        val process = aliveProcess()
        val launcher = SshProcessLauncher { _, _ -> process }
        val neverReachable = TunnelReachabilityProbe { _, _, _ -> false }

        assertThatThrownBy { service(probe = neverReachable, launcher = launcher).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        verify(process).destroyForcibly()
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `verify loop succeeds once the probe reports the tunnel reachable`() {
        // Process stays alive; the probe is not reachable on the first two polls, then is. The loop
        // must keep polling and return normally on the third — no exception.
        val process = aliveProcess()
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false, false, true)

        service(probe = probe).verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())

        verify(probe, times(3)).isReachable(1080, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop throws when the probe never reports the tunnel reachable`() {
        // A live ssh whose tunnel is a black hole (probe always false) must time out and fail, not
        // hang or succeed — the fail-fast contract.
        val process = aliveProcess()
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false)

        assertThatThrownBy {
            service(probe = probe).verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SOCKS5 proxy")
    }

    @Test
    fun `verify loop fails immediately with the ssh exit code when the process is already dead`() {
        // Liveness is checked FIRST, before the probe: a dead ssh (e.g. a changed host key) must
        // abort at once with its exit code, never polling the probe against a corpse.
        val process = deadProcess(exitCode = 255)
        val probe = mock<TunnelReachabilityProbe>()

        assertThatThrownBy {
            service(probe = probe).verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ssh exited with code 255")
        verify(probe, never()).isReachable(any<Int>(), any<String>(), any<Int>())
    }

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
}
