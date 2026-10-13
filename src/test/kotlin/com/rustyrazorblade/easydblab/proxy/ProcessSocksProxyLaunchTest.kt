package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

/** [ProcessSocksProxyService] starting a tunnel: the command line, the appended log, the verify loop, the bind-race retry, what a verified start records, and the reading of an ssh transcript. */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
internal class ProcessSocksProxyLaunchTest : ProcessSocksProxyServiceTestBase() {
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
    fun `getLocalPort is zero when the state file does not decode`() {
        stateFile().writeText("{ not the state file")

        assertThat(service().getLocalPort()).isEqualTo(0)
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
    fun `the tunnel command runs the workspace's edl-socks-tunnel under nohup for the recorded alias`() {
        val command =
            service().buildTunnelCommand(port = 41234, sshConfigPath = "/work/sshConfig", logFile = logFile(), alias = "gw-alias")

        // The script runs ssh with the dynamic forward and ExitOnForwardFailure itself. The alias is
        // the host it dials; a hardcoded control0 would show up here instead of the passed alias.
        // /bin/sh reads the script as data: an exec of a just-rewritten script waits on the macOS scan.
        assertThat(command).containsExactly(
            "nohup",
            "/bin/sh",
            File(tempDir, "${Constants.ToolWrappers.DIRECTORY}/${Constants.ToolWrappers.TUNNEL_SCRIPT}").absolutePath,
            "41234",
            "/work/sshConfig",
            "${Constants.Proxy.TUNNEL_RESTART_BACKOFF_SECONDS}",
            logFile().absolutePath,
            "gw-alias",
        )
    }

    @Test
    fun `the process the launch command starts passes the identity check its record is stopped by`() {
        val command =
            service().buildTunnelCommand(
                port = STOP_PORT,
                sshConfigPath = sshConfigPath(),
                logFile = logFile(),
                alias = "control0",
            )
        // nohup execs the rest of its arguments: the OS then reports /bin/sh as the command and the
        // script path, then the script's own arguments, as its arguments.
        val launched = FakeTunnelProcess(livePid, command[1], command.drop(2))
        val record = staleRecord().copy(pid = livePid.toInt())

        assertThat(seeing(launched).status(record)).isEqualTo(RecordedTunnelStatus.RUNNING)
    }

    @Test
    fun `each launch appends a timestamped header naming its port and alias, and keeps the earlier log`() {
        logFile().apply {
            parentFile.mkdirs()
            writeText("an earlier tunnel's line\n")
        }
        val clock = Clock.fixed(Instant.parse("2026-10-11T23:23:38.517Z"), ZoneOffset.UTC)

        service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 }, clock = clock)
            .ensureRunning(testHost)

        assertThat(logFile().readLines()).containsExactly(
            "an earlier tunnel's line",
            "2026-10-11T23:23:38Z easy-db-lab: launching the SOCKS5 tunnel on port 41234 to control0",
        )
    }

    @Test
    fun `a bind failure an earlier launch logged does not make this launch retry on another port`() {
        logFile().parentFile.mkdirs()
        writeBindFailureTranscript(logFile(), port = 41000)
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, logFile ->
                launched.add(command)
                logFile.appendText("Permission denied (publickey).\n")
                deadProcess(exitCode = 255)
            }

        assertThatThrownBy { service(launcher = launcher).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Permission denied")
            .hasMessageNotContaining("Address already in use")

        assertThat(launched).hasSize(1)
    }

    @Test
    fun `a fresh start writes the tunnel script before it launches it`() {
        val script = File(tempDir, "${Constants.ToolWrappers.DIRECTORY}/${Constants.ToolWrappers.TUNNEL_SCRIPT}")
        var writtenAtLaunch = false
        val launcher =
            SshProcessLauncher { _, _ ->
                writtenAtLaunch = script.canExecute()
                aliveProcess()
            }

        service(probe = { _, _, _ -> true }, launcher = launcher).ensureRunning(testHost)

        assertThat(writtenAtLaunch).withFailMessage("the tunnel script was not written before the launch").isTrue()
    }

    @Test
    fun `a start that fails verification ends the script's ssh too, not only the script`() {
        val ssh = FakeTunnelProcess.ssh(FAKE_PID + 1, DEFAULT_TEST_PORT, File(tempDir, "sshConfig").absolutePath)
        val process =
            mock<Process> {
                on { isAlive } doReturn true
                on { pid() } doReturn FAKE_PID
                on { descendants() } doAnswer {
                    java.util.stream.Stream
                        .of(ssh.handle)
                }
            }

        assertThatThrownBy { service(probe = { _, _, _ -> false }, launcher = { _, _ -> process }).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        verify(process).destroyForcibly()
        assertThat(ssh.handle.isAlive).withFailMessage("the script's ssh must not outlive a failed start").isFalse()
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
        val ports = ArrayDeque(listOf(52000, 41234))
        val launched = mutableListOf<List<String>>()
        val launcher =
            SshProcessLauncher { command, logFile ->
                launched.add(command)
                if (launched.size == 1) {
                    writeBindFailureTranscript(logFile, port = 52000)
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

        assertThat(dynamicForwardPorts(launched)).containsExactly(52000, 41234)
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
                logFile.appendText("Permission denied (publickey).\n")
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
    fun `a verified start records its port and its script PID in the proxy env file and keeps the Tailscale flag`() {
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }

        service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 })
            .ensureRunning(testHost)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = RecordedTunnel(41234, FAKE_PID)))
    }

    @Test
    fun `a script that exits before its port is recorded fails the start and leaves nothing recorded`() {
        // The script reads the env file when its ssh exits: an ssh that dies between the probe and the
        // record finds no port, so the script exits instead of restarting, and the recorded tunnel is dead.
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }
        val exitsAtRecord: Process =
            mock {
                on { isAlive } doAnswer { envFile.read().tunnel == null }
                on { exitValue() } doReturn 255
                on { pid() } doReturn FAKE_PID
            }

        val failure =
            catchThrowable {
                service(probe = { _, _, _ -> true }, launcher = { _, _ -> exitsAtRecord }, portSelector = { 41234 }).ensureRunning(testHost)
            }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("exited right after it was verified")
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = null))
        assertThat(stateFile()).doesNotExist()
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a tunnel whose ssh dies before the record while its script still runs fails the start and is stopped`() {
        // The script is alive at the check, but its ssh died after the probe and before the record:
        // the script will exit when it reads the env file, so the recorded tunnel would be dead.
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }
        // The ssh the script started last, still its descendant; it must not outlive the script.
        val ssh = mock<ProcessHandle>()
        val scriptStaysUp: Process =
            mock {
                on { isAlive } doReturn true
                on { pid() } doReturn FAKE_PID
                on { descendants() } doAnswer { Stream.of(ssh) }
            }
        val sshGoneAtRecord = TunnelReachabilityProbe { _, _, _ -> envFile.read().tunnel == null }

        val failure =
            catchThrowable {
                service(probe = sshGoneAtRecord, launcher = { _, _ -> scriptStaysUp }, portSelector = { 41234 }).ensureRunning(testHost)
            }

        assertThat(
            failure,
        ).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("stopped carrying traffic right after it was verified")
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = null))
        assertThat(stateFile()).doesNotExist()
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
        verify(scriptStaysUp).destroyForcibly()
        verify(ssh).destroyForcibly()
    }

    @Test
    fun `a failed start leaves no port in the env file`() {
        writeStateFile(pid = -1, port = 52000)
        val envFile =
            ProxyEnvFile(tempDir).apply {
                recordTailscale(active = false)
                recordTunnel(52000, ProcessHandle.current().pid())
            }

        assertThatThrownBy { service(launcher = { _, _ -> deadProcess(exitCode = 255) }).ensureRunning(testHost) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = null))
    }

    @Test
    fun `the proxy state file is replaced by rename, so a reader of the old file still sees all of it`() {
        writeStateFile(pid = -1, port = 52000)
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

    @Test
    fun `isLocalPortBindFailure recognizes ssh's dynamic-forward bind errors`() {
        assertThat(isLocalPortBindFailure(listOf("channel_setup_fwd_listener_tcpip: cannot listen to port: 52000"))).isTrue()
        assertThat(isLocalPortBindFailure(listOf("Permission denied (publickey).", "Connection refused"))).isFalse()
        assertThat(isLocalPortBindFailure(emptyList())).isFalse()
    }

    @Test
    fun `an Address already in use line alone is not a bind failure`() {
        // ssh -v prints it for one loopback address it could not bind while it binds another and keeps running.
        val transcript = listOf("debug1: Local forwarding listening on 127.0.0.1 port 52000.", "bind [::1]:52000: Address already in use")

        assertThat(isLocalPortBindFailure(transcript)).isFalse()
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

        service(probe = probe).verifyTunnelReachable(process, port = 52000, gatewayHost = testHost, logFile = logFile())

        verify(probe, times(3)).isReachable(52000, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop throws when the probe never reports the tunnel reachable`() {
        // A live ssh whose tunnel is a black hole (probe always false) must time out and fail, not
        // hang or succeed — the fail-fast contract.
        val process = aliveProcess()
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false)

        assertThatThrownBy {
            service(probe = probe).verifyTunnelReachable(process, port = 52000, gatewayHost = testHost, logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SOCKS5 proxy")
        verify(probe, times(Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS)).isReachable(52000, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop timeout names the gateway alias and the time it really waited`() {
        // The address comes from ssh's own dial line; the name must be the gateway's alias. Each
        // probe runs to its own timeout, so the wait is what the clock measured, not attempts x delay.
        val log = logFile().apply { parentFile.mkdirs() }
        log.writeText("debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.\n")
        val clock = ManualClock()
        val slowProbe = TunnelReachabilityProbe { _, _, _ -> false.also { clock.advance(Duration.ofMillis(1_400)) } }

        assertThatThrownBy {
            service(probe = slowProbe, clock = clock)
                .verifyTunnelReachable(aliveProcess(), port = 52000, gatewayHost = testHost, logFile = log)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ssh could not connect to control0 (54.1.2.3) port 22 within 14s.")
    }

    @Test
    fun `verify loop reports a dial that ended in an ssh exit by its exit code, not as a stalled connect`() {
        // ssh dialed, was alive for the first checks, then gave up with 255: its own error explains it.
        val log = logFile().apply { parentFile.mkdirs() }
        log.writeText("debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.\n")
        val process =
            mock<Process> {
                on { isAlive } doReturn true doReturn true doReturn false
                on { exitValue() } doReturn 255
                on { pid() } doReturn FAKE_PID
            }

        assertThatThrownBy {
            service(connectFailureHint = { DirectSshRoute(sshPort = 22).tunnelConnectFailureHint(sshOpenToAll = false) })
                .verifyTunnelReachable(process, port = 52000, gatewayHost = testHost, logFile = log)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ssh exited with code 255")
            .hasMessageNotContaining("could not connect")
            .hasMessageNotContaining("security group")
    }

    @Test
    fun `a hint that cannot be looked up neither replaces the tunnel failure nor escapes the verify loop`() {
        // The loop must still throw IllegalStateException, the one the launch catches to stop the
        // script and its still-dialing ssh; a hint lookup that throws must not get past it.
        val log = logFile().apply { parentFile.mkdirs() }
        log.writeText("debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.\n")
        val brokenHint: () -> String? = { throw java.io.UncheckedIOException(java.io.IOException("state.json is corrupt")) }

        assertThatThrownBy {
            service(
                connectFailureHint = brokenHint,
            ).verifyTunnelReachable(aliveProcess(), port = 52000, gatewayHost = testHost, logFile = log)
        }.isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ssh could not connect to control0 (54.1.2.3) port 22 within")
            .hasMessageNotContaining("security group")
            .hasMessageNotContaining("corrupt")
    }

    @Test
    fun `verify loop probes for the attempt budget the SSH route supplies`() {
        // The ssm transport's slower tunnel set-up gets a longer budget; a direct tunnel keeps its short one.
        val process = aliveProcess()
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false)

        assertThatThrownBy {
            service(probe = probe, verifyAttempts = Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS)
                .verifyTunnelReachable(process, port = 52000, gatewayHost = testHost, logFile = logFile())
        }.isInstanceOf(IllegalStateException::class.java)

        verify(probe, times(Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS)).isReachable(52000, "10.0.1.5", SSH_PORT)
    }

    @Test
    fun `verify loop fails immediately with the ssh exit code when the process is already dead`() {
        // Liveness is checked FIRST, before the probe: a dead ssh (e.g. a changed host key) must
        // abort at once with its exit code, never polling the probe against a corpse.
        val process = deadProcess(exitCode = 255)
        val probe = mock<TunnelReachabilityProbe>()

        assertThatThrownBy {
            service(probe = probe).verifyTunnelReachable(process, port = 52000, gatewayHost = testHost, logFile = logFile())
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
