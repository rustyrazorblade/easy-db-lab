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
 * socket (proxy reuse, the zombie-port connect) remain in the integration tier in
 * `ProcessSocksProxyServiceTest`; the port-fallback bind is in `LoopbackPortSelectorTest`.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class ProcessSocksProxyServiceUnitTest {
    private companion object {
        /** Tiny verify delay so the verify loop's retries do not sleep the production 500ms each. */
        val VERIFY_DELAY: Duration = Duration.ofMillis(1)

        /** Arbitrary PID returned by fake processes; never inspected for liveness by these tests. */
        const val FAKE_PID = 4242L

        /** Port the default fake selector hands out; nothing ever binds it. */
        const val DEFAULT_TEST_PORT = 1080

        /** Port a recorded tunnel listens on in the stop tests; nothing binds it. */
        const val STOP_PORT = 41234

        /** Port a replacement tunnel is started on; nothing binds it. */
        const val NEW_PORT = 45678
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
     * Builds a service with an injected [probe] and [launcher]. The default launcher fails loudly if
     * called, so tests that must not reach a fresh ssh spawn are protected against accidentally
     * doing so; tests that exercise a spawn pass an explicit fake.
     */
    private fun service(
        probe: TunnelReachabilityProbe = TunnelReachabilityProbe { _, _, _ -> false },
        launcher: SshProcessLauncher =
            SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
        portSelector: LocalPortSelector = LocalPortSelector { DEFAULT_TEST_PORT },
        verifyAttempts: Int = Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS,
        tunnelProcesses: TunnelProcessControl = TunnelProcessControl(),
    ) = ProcessSocksProxyService(
        Context.forCli(tempDir).copy(workingDirectory = tempDir),
        probe,
        verifyDelay = VERIFY_DELAY,
        processLauncher = launcher,
        portSelector = portSelector,
        verifyAttempts = verifyAttempts,
        tunnelProcesses = tunnelProcesses,
    )

    /** A service whose process lookup sees only [process]. */
    private fun serviceSeeing(
        process: FakeTunnelProcess?,
        probe: TunnelReachabilityProbe = TunnelReachabilityProbe { _, _, _ -> false },
        launcher: SshProcessLauncher = SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
    ) = service(
        probe = probe,
        launcher = launcher,
        portSelector = { STOP_PORT },
        tunnelProcesses =
            TunnelProcessControl(lookup = { pid -> process?.takeIf { it.pid == pid }?.handle }, stopWait = Duration.ofMillis(50)),
    )

    private fun stateFile() = File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)

    private fun recordedEnv() =
        ProxyEnvFile(tempDir).apply {
            recordTailscale(active = false)
            recordPort(STOP_PORT)
        }

    /** The `-D` port each launched ssh command was handed, in launch order. */
    private fun dynamicForwardPorts(launched: List<List<String>>): List<Int> = launched.map { it[it.indexOf("-D") + 1].toInt() }

    /** Writes what ssh prints when another process already holds its `-D` port. */
    private fun writeBindFailureTranscript(
        logFile: File,
        port: Int,
    ) {
        logFile.writeText(
            """
            debug1: Local connections to LOCALHOST:$port forwarded to remote address socks:0
            bind [127.0.0.1]:$port: Address already in use
            channel_setup_fwd_listener_tcpip: cannot listen to port: $port
            Could not request local forwarding.
            """.trimIndent(),
        )
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
    fun `a local port bind failure relaunches ssh on a newly selected port`() {
        // Two workspaces starting a proxy at once can both find the same port free; the loser's ssh
        // dies with "Address already in use". The service must pick a new port and relaunch, not
        // fail the command.
        val ports = ArrayDeque(listOf(1080, 41234))
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, logFile ->
                launched.add(command)
                if (launched.size == 1) {
                    writeBindFailureTranscript(logFile, port = 1080)
                    deadProcess(exitCode = 255)
                } else {
                    aliveProcess()
                }
            }

        val state =
            service(
                probe = { _, _, _ -> true },
                launcher = launcher,
                portSelector = { ports.removeFirst() },
            ).ensureRunning(testHost)

        assertThat(dynamicForwardPorts(launched)).containsExactly(1080, 41234)
        assertThat(state.localPort).isEqualTo(41234)
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("41234")
        val recorded =
            json.decodeFromString<Socks5ProxyStateFile>(File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).readText())
        assertThat(recorded.port).isEqualTo(41234)
    }

    @Test
    fun `an ssh failure that is not a local port bind failure is not retried`() {
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, logFile ->
                launched.add(command)
                logFile.writeText("Permission denied (publickey).\n")
                deadProcess(exitCode = 255)
            }

        assertThatThrownBy { service(launcher = launcher).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Permission denied")

        assertThat(launched).hasSize(1)
    }

    @Test
    fun `a local port bind failure on every attempt gives up after the retry budget`() {
        val nextPort = generateSequence(42000) { it + 1 }.iterator()
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, logFile ->
                launched.add(command)
                writeBindFailureTranscript(logFile, port = dynamicForwardPorts(listOf(command)).single())
                deadProcess(exitCode = 255)
            }

        assertThatThrownBy {
            service(launcher = launcher, portSelector = { nextPort.next() }).ensureRunning(testHost)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Address already in use")

        assertThat(launched).hasSize(Constants.Proxy.PORT_BIND_MAX_ATTEMPTS)
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a verified start records its port in the proxy env file and keeps the Tailscale flag`() {
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }

        service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 })
            .ensureRunning(testHost)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = 41234))
    }

    @Test
    fun `a stale PID is replaced by a tunnel on a new port, and the env file records the new port`() {
        writeStateFile(pid = -1, port = 1080)
        val envFile = ProxyEnvFile(tempDir).apply { recordPort(1080) }

        service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 })
            .ensureRunning(testHost)

        assertThat(envFile.read().socksPort).isEqualTo(41234)
        val recorded =
            json.decodeFromString<Socks5ProxyStateFile>(File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).readText())
        assertThat(recorded.port).isEqualTo(41234)
    }

    @Test
    fun `a failed start leaves no port in the env file`() {
        writeStateFile(pid = -1, port = 1080)
        val envFile =
            ProxyEnvFile(tempDir).apply {
                recordTailscale(active = false)
                recordPort(1080)
            }

        assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
    }

    @Test
    fun `the proxy state file is replaced by rename, so a reader of the old file still sees all of it`() {
        writeStateFile(pid = -1, port = 1080)
        val stateFile = File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)
        val before = stateFile.readText()

        stateFile.inputStream().use { oldReader ->
            service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 })
                .ensureRunning(testHost)

            assertThat(String(oldReader.readAllBytes())).isEqualTo(before)
        }
        assertThat(json.decodeFromString<Socks5ProxyStateFile>(stateFile.readText()).port).isEqualTo(41234)
        assertThat(tempDir.list()).noneMatch { it.endsWith(".tmp") }
    }

    /**
     * A live PID that is not this JVM's, which the stop path refuses to signal: the test runner's parent.
     * The service's liveness check sees it alive; every lookup here is faked, so it is never signaled.
     */
    private val livePid: Long = requireNotNull(ProcessHandle.current().parent().orElse(null)) { "the test JVM has no parent" }.pid()

    /** A process lookup that finds nothing, so a stale-tunnel stop never reaches a real process. */
    private val noProcesses = TunnelProcessControl(lookup = { null })

    /** A launched ssh that stays alive under [livePid], so the in-memory reuse path sees it running. */
    private fun liveTunnelProcess(): Process =
        mock {
            on { isAlive } doReturn true
            on { pid() } doReturn livePid
        }

    /** A probe that reports reachable only through the ports in [healthy], counting every call per port. */
    private class PortProbe(
        vararg healthy: Int,
    ) : TunnelReachabilityProbe {
        val healthy = healthy.toMutableSet()
        val calls = mutableMapOf<Int, Int>()

        override fun isReachable(
            localSocksPort: Int,
            targetHost: String,
            targetPort: Int,
        ): Boolean {
            calls.merge(localSocksPort, 1, Int::plus)
            return localSocksPort in healthy
        }
    }

    @Test
    fun `a recorded tunnel that passes the end-to-end probe is reused and its port recorded`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }

        val state = service(probe = PortProbe(STOP_PORT)).ensureRunning(testHost)

        assertThat(state.reused).isTrue()
        assertThat(state.localPort).isEqualTo(STOP_PORT)
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = STOP_PORT))
    }

    @Test
    fun `a recorded tunnel whose far end is dead is stopped and replaced, and only the new port is recorded`() {
        // The live-cluster failure: the ssh process and its local port stay up after the SSH
        // connection died, so only an end-to-end probe tells the tunnel is useless.
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val zombie = FakeTunnelProcess.sshTunnel(livePid, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
        val probe = PortProbe(NEW_PORT)
        val launched = mutableListOf<List<String>>()

        val state =
            service(
                probe = probe,
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { NEW_PORT },
                tunnelProcesses = TunnelProcessControl(lookup = { pid -> zombie.handle.takeIf { pid == livePid } }),
            ).ensureRunning(testHost)

        assertThat(zombie.handle.isAlive).withFailMessage("the dead tunnel must be stopped, not leaked").isFalse()
        assertThat(dynamicForwardPorts(launched)).containsExactly(NEW_PORT)
        assertThat(state.reused).isFalse()
        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = NEW_PORT))
        assertThat(json.decodeFromString<Socks5ProxyStateFile>(stateFile().readText()).port).isEqualTo(NEW_PORT)
    }

    @Test
    fun `a recorded tunnel that fails the probe and cannot be replaced leaves no port recorded`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()

        assertThatThrownBy {
            service(probe = PortProbe(), launcher = { _, _ -> deadProcess(exitCode = 255) }, tunnelProcesses = noProcesses)
                .ensureRunning(testHost)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(envFile.read().socksPort).isNull()
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a recorded tunnel is probed a bounded number of times before it is replaced`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val probe = PortProbe(NEW_PORT)

        service(probe = probe, launcher = { _, _ -> liveTunnelProcess() }, portSelector = { NEW_PORT }, tunnelProcesses = noProcesses)
            .ensureRunning(testHost)

        assertThat(probe.calls[STOP_PORT]).isEqualTo(Constants.Proxy.REUSE_TUNNEL_VERIFY_ATTEMPTS)
    }

    @Test
    fun `a recorded tunnel that fails one probe and passes the next is reused`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false, true)

        val state = service(probe = probe).ensureRunning(testHost)

        assertThat(state.reused).isTrue()
        assertThat(state.localPort).isEqualTo(STOP_PORT)
    }

    @Test
    fun `the in-memory tunnel is reused while it passes the probe`() {
        val launched = mutableListOf<List<String>>()
        val svc =
            service(
                probe = PortProbe(STOP_PORT),
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { STOP_PORT },
            )
        svc.ensureRunning(testHost)
        val envFile = ProxyEnvFile(tempDir).apply { removePort() }

        val second = svc.ensureRunning(testHost)

        assertThat(launched).hasSize(1)
        assertThat(second.reused).isTrue()
        assertThat(envFile.read().socksPort).isEqualTo(STOP_PORT)
    }

    @Test
    fun `an in-memory tunnel whose far end died is stopped and replaced, and only the new port is recorded`() {
        val ports = ArrayDeque(listOf(STOP_PORT, NEW_PORT))
        val launched = mutableListOf<List<String>>()
        val probe = PortProbe(STOP_PORT, NEW_PORT)
        val zombie = FakeTunnelProcess.sshTunnel(livePid, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
        val svc =
            service(
                probe = probe,
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { ports.removeFirst() },
                tunnelProcesses = TunnelProcessControl(lookup = { pid -> zombie.handle.takeIf { pid == livePid } }),
            )
        svc.ensureRunning(testHost)
        probe.healthy.remove(STOP_PORT)

        val second = svc.ensureRunning(testHost)

        assertThat(zombie.handle.isAlive).withFailMessage("the dead tunnel must be stopped, not leaked").isFalse()
        assertThat(dynamicForwardPorts(launched)).containsExactly(STOP_PORT, NEW_PORT)
        assertThat(second.reused).isFalse()
        assertThat(second.localPort).isEqualTo(NEW_PORT)
        assertThat(ProxyEnvFile(tempDir).read().socksPort).isEqualTo(NEW_PORT)
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("$NEW_PORT")
    }

    @Test
    fun `stop ends the recorded tunnel and removes the state file and the port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val tunnel = FakeTunnelProcess.sshTunnel(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)

        val result = serviceSeeing(tunnel).stop()

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(FAKE_PID.toInt()))
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
    }

    @Test
    fun `stop with a dead PID reports no tunnel and removes the state file and the port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()

        val result = serviceSeeing(null).stop()

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read().socksPort).isNull()
    }

    @Test
    fun `stop never signals a recorded PID that another program now holds`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val other = FakeTunnelProcess(FAKE_PID, "/usr/bin/python3", listOf("server.py"))

        val result = serviceSeeing(other).stop()

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(other.signals).isZero()
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read().socksPort).isNull()
    }

    @Test
    fun `a tunnel that will not stop keeps its state file and its port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val hung = FakeTunnelProcess.sshTunnel(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath, endsOnSignal = false)

        val result = serviceSeeing(hung).stop()

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(FAKE_PID.toInt()))
        assertThat(stateFile()).exists()
        assertThat(envFile.read().socksPort).isEqualTo(STOP_PORT)
    }

    @Test
    fun `stop with a corrupt state file removes it and the port`() {
        stateFile().writeText("{ not json")
        val envFile = recordedEnv()

        val result = serviceSeeing(null).stop()

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read().socksPort).isNull()
    }

    /** The record a superseded tunnel left: what ensureRunning has just found not reusable (issue #741). */
    private fun staleRecord() =
        Socks5ProxyStateFile(
            pid = FAKE_PID.toInt(),
            port = STOP_PORT,
            controlHost = "control0",
            controlIP = testHost.privateIp,
            clusterName = tempDir.name,
            startTime = Instant.now().toString(),
            sshConfig = File(tempDir, "sshConfig").absolutePath,
        )

    @Test
    fun `a superseded tunnel that is still our ssh is terminated before its record is overwritten`() {
        val zombie = FakeTunnelProcess.sshTunnel(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)

        serviceSeeing(zombie).terminateStaleProxy(staleRecord())

        assertThat(zombie.handle.isAlive).isFalse()
    }

    @Test
    fun `a superseded record whose PID another program now holds signals nothing`() {
        val other = FakeTunnelProcess(FAKE_PID, "/usr/bin/python3", listOf("server.py"))

        serviceSeeing(other).terminateStaleProxy(staleRecord())

        assertThat(other.signals).isZero()
        assertThat(other.handle.isAlive).isTrue()
    }

    @Test
    fun `a superseded record whose PID is gone is ignored`() {
        // The lookup sees no process at all; nothing to signal, and nothing throws.
        serviceSeeing(null).terminateStaleProxy(staleRecord())
    }

    @Test
    fun `stop with a corrupt state file still stops the tunnel this process started`() {
        val tunnel = FakeTunnelProcess.sshTunnel(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
        val svc = serviceSeeing(tunnel, probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() })
        svc.ensureRunning(testHost)
        stateFile().writeText("{ not json")

        val result = svc.stop()

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(FAKE_PID.toInt()))
        assertThat(tunnel.handle.isAlive).isFalse()
        assertThat(stateFile()).doesNotExist()
    }

    @Test
    fun `stop with nothing recorded reports no tunnel`() {
        assertThat(serviceSeeing(null).stop()).isEqualTo(TunnelStopResult.NotRunning)
    }

    @Test
    fun `isLocalPortBindFailure recognizes ssh's dynamic-forward bind errors`() {
        assertThat(isLocalPortBindFailure(listOf("bind [127.0.0.1]:1080: Address already in use"))).isTrue()
        assertThat(isLocalPortBindFailure(listOf("channel_setup_fwd_listener_tcpip: cannot listen to port: 1080"))).isTrue()
        assertThat(isLocalPortBindFailure(listOf("Permission denied (publickey).", "Connection refused"))).isFalse()
        assertThat(isLocalPortBindFailure(emptyList())).isFalse()
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
        verify(probe, times(Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS)).isReachable(1080, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop probes for the attempt budget the SSH route supplies`() {
        // The ssm transport's slower tunnel set-up gets a longer budget; a direct tunnel keeps its short one.
        val process = aliveProcess()
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false)

        assertThatThrownBy {
            service(probe = probe, verifyAttempts = Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS)
                .verifyTunnelReachable(process, port = 1080, targetPrivateIp = "10.0.1.5", logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)

        verify(probe, times(Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS)).isReachable(1080, "10.0.1.5", SSH_PORT)
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
