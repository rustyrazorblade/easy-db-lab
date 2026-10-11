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
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Integration-tier tests for [ProcessSocksProxyService] that genuinely require real socket I/O: the
 * reuse path, where the real [SocksTunnelReachabilityProbe] goes through a [FakeSocksTunnel] to prove
 * the tunnel carries traffic, and the zombie tunnels it must reject: a port that refuses connections,
 * and a port that accepts them while the far end is dead. The port-fallback bind is covered by `LoopbackPortSelectorTest`.
 * Every port here is OS-assigned via `ServerSocket(0)` — no test binds a hardcoded port, so two of these running on a busy CI runner
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

        /** The real probe's connect and read timeout; every tunnel here is on the loopback. */
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
        File(tempDir, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @AfterEach
    fun tearDown() {
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
        portSelector: LocalPortSelector = LoopbackPortSelector(),
        clock: Clock = Clock.systemUTC(),
    ) = ProcessSocksProxyService(
        Context.forCli(tempDir).copy(workingDirectory = tempDir),
        SocksTunnelReachabilityProbe(PROBE_TIMEOUT_MS),
        verifyDelay = VERIFY_DELAY,
        processLauncher = launcher,
        portSelector = portSelector,
        clock = clock,
    )

    /**
     * A clock that moves past [Constants.Proxy.REUSE_PROBE_FRESHNESS] each time it is read, so every
     * in-memory reuse probes the tunnel again, without the test sleeping.
     */
    private class OutsideFreshnessClock : Clock() {
        private var now = Instant.parse("2026-10-10T12:00:00Z")

        override fun instant(): Instant = now.also { now = now.plus(Constants.Proxy.REUSE_PROBE_FRESHNESS).plusSeconds(1) }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

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
    fun `reuses proxy when state file has a live PID, matching IP, and a tunnel that carries traffic`() {
        // Use this JVM's PID as a live PID, and serve a working SOCKS tunnel on the recorded port —
        // isValidProxy() probes through it, not just the PID, so a merely-recorded port, or one whose
        // far end is dead, would (correctly) be rejected as a zombie tunnel rather than reused.
        val livePid = ProcessHandle.current().pid().toInt()
        FakeSocksTunnel().use { tunnel ->
            val port = tunnel.port
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
    fun `reusing a verified proxy records its port in the env file and keeps the Tailscale flag`() {
        val livePid = ProcessHandle.current().pid().toInt()
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }
        FakeSocksTunnel().use { tunnel ->
            val port = tunnel.port
            writeStateFile(pid = livePid, port = port)

            service().ensureRunning(testHost)

            assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = port))
        }
    }

    @Test
    fun `publishes the proxy port property but never the global socksProxyHost when reusing a valid proxy`() {
        val livePid = ProcessHandle.current().pid().toInt()
        FakeSocksTunnel().use { tunnel ->
            val port = tunnel.port
            writeStateFile(pid = livePid, port = port)

            service().ensureRunning(testHost)

            // The private port property is published for the cluster clients...
            assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("$port")
            // ...but the standard global socksProxyHost is NOT set, so java.net (and the AWS SDK) stay direct.
            assertThat(System.getProperty("socksProxyHost")).isNull()
        }
    }

    /**
     * Stands in for `ssh -D`: binds the command's `-D` port on the IPv4 loopback with SO_REUSEADDR
     * off, the way ssh does, and holds it. When the port is already taken it writes ssh's bind error
     * to the transcript and returns an exited process, as `ExitOnForwardFailure=yes` makes ssh do.
     */
    private class LoopbackBindingLauncher : SshProcessLauncher {
        val listeners = mutableListOf<ServerSocket>()
        val attemptedPorts = mutableListOf<Int>()

        override fun launch(
            command: List<String>,
            logFile: File,
        ): Process {
            val port = command[command.indexOf("-D") + 1].toInt()
            attemptedPorts.add(port)
            val socket = ServerSocket().apply { reuseAddress = false }
            return try {
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                listeners.add(socket)
                mock {
                    on { isAlive } doReturn true
                    on { pid() } doReturn FAKE_PID
                }
            } catch (_: BindException) {
                socket.close()
                logFile.writeText("bind [127.0.0.1]:$port: Address already in use\ncannot listen to port: $port\n")
                mock {
                    on { isAlive } doReturn false
                    on { exitValue() } doReturn 255
                    on { pid() } doReturn FAKE_PID
                }
            }
        }

        fun close() = listeners.forEach { it.close() }
    }

    private fun workspace(name: String): File =
        File(tempDir, name).apply {
            mkdirs()
            File(this, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        }

    private fun serviceIn(
        workspace: File,
        launcher: SshProcessLauncher,
        portSelector: LocalPortSelector,
    ) = ProcessSocksProxyService(
        Context.forCli(workspace).copy(workingDirectory = workspace),
        TunnelReachabilityProbe { _, _, _ -> true },
        verifyDelay = VERIFY_DELAY,
        processLauncher = launcher,
        portSelector = portSelector,
    )

    @Test
    fun `proxies for two workspaces listen on different local ports`() {
        val preferred = reserveFreePort()
        val launcher = LoopbackBindingLauncher()
        try {
            val first = serviceIn(workspace("cluster-a"), launcher, LoopbackPortSelector(preferred)).ensureRunning(testHost)
            val second = serviceIn(workspace("cluster-b"), launcher, LoopbackPortSelector(preferred)).ensureRunning(testHost)

            assertThat(first.localPort).isEqualTo(preferred)
            assertThat(second.localPort).isNotEqualTo(first.localPort)
        } finally {
            launcher.close()
        }
    }

    @Test
    fun `a proxy that loses its port to a concurrent workspace relaunches on another port`() {
        // The race: both workspaces probe the preferred port while it is free, then cluster-a's ssh
        // binds it first. cluster-b's selector therefore hands out the taken port once, as its stale
        // probe would, and its ssh fails to bind. The start must recover on a new port.
        val preferred = reserveFreePort()
        val launcher = LoopbackBindingLauncher()
        try {
            val first = serviceIn(workspace("cluster-a"), launcher, LoopbackPortSelector(preferred)).ensureRunning(testHost)
            val live = LoopbackPortSelector(preferred)
            var staleProbe = true
            val racingSelector =
                LocalPortSelector {
                    if (staleProbe) {
                        staleProbe = false
                        preferred
                    } else {
                        live.select()
                    }
                }

            val second = serviceIn(workspace("cluster-b"), launcher, racingSelector).ensureRunning(testHost)

            assertThat(launcher.attemptedPorts).containsExactly(preferred, preferred, second.localPort)
            assertThat(second.localPort).isNotEqualTo(first.localPort)
        } finally {
            launcher.close()
        }
    }

    @Test
    fun `a tunnel whose port accepts while its far end is dead is replaced, and only the new port is recorded`() {
        // The live-cluster failure: the SSH connection of an idle tunnel died while ssh and its local
        // port stayed up. The real probe gets "Malformed reply from SOCKS server" through it.
        val livePid = ProcessHandle.current().pid().toInt()
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }
        FakeSocksTunnel(farEndAlive = false).use { dead ->
            FakeSocksTunnel().use { replacement ->
                writeStateFile(pid = livePid, port = dead.port)
                envFile.recordPort(dead.port)
                val alive: Process =
                    mock {
                        on { isAlive } doReturn true
                        on { pid() } doReturn FAKE_PID
                    }

                val state = service(launcher = { _, _ -> alive }, portSelector = { replacement.port }).ensureRunning(testHost)

                assertThat(state.reused).isFalse()
                assertThat(state.localPort).isEqualTo(replacement.port)
                assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = replacement.port))
                assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("${replacement.port}")
            }
        }
    }

    @Test
    fun `a live PID whose recorded port is not listening is not reused`() {
        val livePid = ProcessHandle.current().pid().toInt()
        val deadPort = reserveFreePort() // recorded but nothing is listening on it

        writeStateFile(pid = livePid, port = deadPort)

        // isValidProxy() rejects the zombie (the probe cannot connect), so a fresh start is attempted; the
        // fake launcher makes that start fail fast so we can observe the zombie was rejected, not reused.
        assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        // The zombie's port must never be republished — it was rejected as invalid, and the fresh
        // attempt also failed.
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a superseded record whose PID a non-ssh process now holds is not killed when a replacement is started`() {
        // The recorded PID is alive but its `-D` port is not listening, so the record is not reused
        // and a replacement is started. A superseded tunnel is killed before its record is
        // overwritten (issue #741), but only when the PID is still that ssh tunnel: here the OS has
        // given the PID to another program, a real `sleep`, which must survive.
        val other = ProcessBuilder("sleep", "60").start()
        try {
            val deadPort = reserveFreePort() // recorded but nothing is listening on it
            writeStateFile(pid = other.pid().toInt(), port = deadPort)

            // The fresh start fails fast (fake dead launcher); we only care what happened to the PID.
            assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
                .isInstanceOf(IllegalStateException::class.java)

            assertThat(other.isAlive).withFailMessage("a PID that is no longer the tunnel must not be killed").isTrue()
        } finally {
            other.destroyForcibly()
        }
    }

    @Test
    fun `a second call reuses the in-memory tunnel and records its port again`() {
        // The first call fills the in-memory state through a genuine reuse; the second takes the
        // in-memory fast path. Removing the port in between models a stop-socks of an old tunnel
        // or a deleted env file: the fast path must record the port again.
        val livePid = ProcessHandle.current().pid().toInt()
        val svc = service()
        val envFile = ProxyEnvFile(tempDir)
        FakeSocksTunnel().use { tunnel ->
            val port = tunnel.port
            writeStateFile(pid = livePid, port = port)
            svc.ensureRunning(testHost)
            envFile.removePort()

            val second = svc.ensureRunning(testHost)

            assertThat(second.reused).isTrue()
            assertThat(second.localPort).isEqualTo(port)
            assertThat(envFile.read().socksPort).isEqualTo(port)
        }
    }

    @Test
    fun `an in-memory port that dies between calls is re-validated, not trusted, on the next call`() {
        // Populate in-memory state via a genuine reuse (a working fake tunnel), then close the
        // tunnel so the recorded port stops accepting while the recorded PID (this JVM) stays
        // alive — the "process alive, tunnel dead" case the in-memory fast path must not trust.
        val livePid = ProcessHandle.current().pid().toInt()
        // Past the freshness window, so the second call probes instead of trusting the first call's probe.
        val svc = service(launcher = { _, _ -> deadProcess(exitCode = 255) }, clock = OutsideFreshnessClock())
        FakeSocksTunnel().use { tunnel ->
            val port = tunnel.port
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
