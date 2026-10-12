package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Duration

/** [ProcessSocksProxyService] deciding between reusing and replacing a tunnel, from memory and from the state file: the end-to-end probe, its freshness window, and the events a replacement emits. */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
internal class ProcessSocksProxyReuseTest : ProcessSocksProxyServiceTestBase() {
    @Test
    fun `a new start stops the orphaned ssh that the recorded tunnel's killed script left`() {
        writeStateFile(pid = -1, port = STOP_PORT)
        val orphan = FakeTunnelProcess.ssh(FAKE_PID + 1, STOP_PORT, File(tempDir, "sshConfig").absolutePath)

        service(
            probe = { _, _, _ -> true },
            launcher = { _, _ -> aliveProcess() },
            portSelector = { NEW_PORT },
            tunnelProcesses =
                TunnelProcessControl(lookup = { null }, allProcesses = {
                    java.util.stream.Stream
                        .of(orphan.handle)
                }),
        ).ensureRunning(testHost)

        assertThat(orphan.handle.isAlive).withFailMessage("the orphaned ssh of the replaced tunnel was left running").isFalse()
    }

    @Test
    fun `a stale PID is replaced by a tunnel on a new port, and the env file records the new port`() {
        writeStateFile(pid = -1, port = 52000)
        val envFile = ProxyEnvFile(tempDir).apply { recordTunnel(52000, ProcessHandle.current().pid()) }

        service(probe = { _, _, _ -> true }, launcher = { _, _ -> aliveProcess() }, portSelector = { 41234 })
            .ensureRunning(testHost)

        assertThat(envFile.read().tunnel?.port).isEqualTo(41234)
        val recorded =
            json.decodeFromString<Socks5ProxyStateFile>(File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).readText())
        assertThat(recorded.port).isEqualTo(41234)
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(52000, 41234, Event.Proxy.ReplaceReason.PROCESS_GONE))
    }

    @Test
    fun `a recorded PID that another program now holds is replaced as gone, and that program is not signaled`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val other = FakeTunnelProcess(livePid, "/bin/sleep", listOf("60"))

        val state =
            service(
                // The recorded port still answers, so only the identity check can tell the tunnel is gone.
                probe = PortProbe(STOP_PORT, NEW_PORT),
                launcher = { _, _ -> aliveProcess() },
                portSelector = { NEW_PORT },
                tunnelProcesses = seeing(other),
            ).ensureRunning(testHost)

        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(other.signals).isZero()
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.PROCESS_GONE))
    }

    @Test
    fun `a recorded tunnel started with another sshConfig is stopped and replaced`() {
        val movedConfig = File(tempDir, "old-workspace/sshConfig").absolutePath
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT, sshConfigPath = movedConfig)
        val old = recordedScript(sshConfig = movedConfig)

        val state =
            service(
                probe = PortProbe(STOP_PORT, NEW_PORT),
                launcher = { _, _ -> aliveProcess() },
                portSelector = { NEW_PORT },
                tunnelProcesses = seeing(old),
            ).ensureRunning(testHost)

        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(old.handle.isAlive).withFailMessage("the tunnel with the old sshConfig was left running").isFalse()
        assertThat(ProxyEnvFile(tempDir).read().tunnel?.port).isEqualTo(NEW_PORT)
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.SSH_CONFIG_CHANGED))
    }

    @Test
    fun `a recorded tunnel to another control node is stopped and replaced`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT, controlIP = "10.0.1.9")
        val old = recordedScript()

        val state =
            service(
                probe = PortProbe(STOP_PORT, NEW_PORT),
                launcher = { _, _ -> aliveProcess() },
                portSelector = { NEW_PORT },
                tunnelProcesses = seeing(old),
            ).ensureRunning(testHost)

        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(old.handle.isAlive).withFailMessage("the tunnel to the old control node was left running").isFalse()
        assertThat(ProxyEnvFile(tempDir).read().tunnel?.port).isEqualTo(NEW_PORT)
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.GATEWAY_CHANGED))
    }

    @Test
    fun `a recorded tunnel that passes the end-to-end probe is reused and its port recorded`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = ProxyEnvFile(tempDir).apply { recordTailscale(active = false) }

        val state = service(probe = PortProbe(STOP_PORT), tunnelProcesses = seeing(recordedScript())).ensureRunning(testHost)

        assertThat(state.reused).isTrue()
        assertThat(state.localPort).isEqualTo(STOP_PORT)
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = RecordedTunnel(STOP_PORT, livePid)))
    }

    @Test
    fun `a recorded tunnel whose far end is dead is stopped and replaced, and only the new port is recorded`() {
        // The live-cluster failure: the ssh process and its local port stay up after the SSH
        // connection died, so only an end-to-end probe tells the tunnel is useless.
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val zombie = recordedScript()
        val probe = PortProbe(NEW_PORT)
        val launched = mutableListOf<List<String>>()

        val state =
            service(
                probe = probe,
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { NEW_PORT },
                tunnelProcesses = seeing(zombie),
            ).ensureRunning(testHost)

        assertThat(zombie.handle.isAlive).withFailMessage("the dead tunnel must be stopped, not leaked").isFalse()
        assertThat(dynamicForwardPorts(launched)).containsExactly(NEW_PORT)
        assertThat(state.reused).isFalse()
        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = RecordedTunnel(NEW_PORT, livePid)))
        assertThat(json.decodeFromString<Socks5ProxyStateFile>(stateFile().readText()).port).isEqualTo(NEW_PORT)
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.UNREACHABLE))
    }

    @Test
    fun `a recorded tunnel that fails the probe and cannot be replaced leaves no port recorded`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()

        assertThatThrownBy {
            service(probe = PortProbe(), launcher = { _, _ -> deadProcess(exitCode = 255) }, tunnelProcesses = seeing(recordedScript()))
                .ensureRunning(testHost)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(envFile.read().tunnel).isNull()
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `a recorded tunnel is probed a bounded number of times before it is replaced`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val probe = PortProbe(NEW_PORT)

        service(
            probe = probe,
            launcher = { _, _ -> liveTunnelProcess() },
            portSelector = { NEW_PORT },
            tunnelProcesses = seeing(recordedScript()),
        ).ensureRunning(testHost)

        assertThat(probe.calls[STOP_PORT]).isEqualTo(Constants.Proxy.REUSE_TUNNEL_VERIFY_ATTEMPTS)
    }

    @Test
    fun `a recorded tunnel that fails one probe and passes the next is reused`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val probe = mock<TunnelReachabilityProbe>()
        whenever(probe.isReachable(any<Int>(), any<String>(), any<Int>())).thenReturn(false, true)

        val state = service(probe = probe, tunnelProcesses = seeing(recordedScript())).ensureRunning(testHost)

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
        val envFile = ProxyEnvFile(tempDir).apply { removeTunnel() }

        val second = svc.ensureRunning(testHost)

        assertThat(launched).hasSize(1)
        assertThat(second.reused).isTrue()
        assertThat(envFile.read().tunnel?.port).isEqualTo(STOP_PORT)
    }

    @Test
    fun `an in-memory tunnel whose far end died is stopped and replaced, and only the new port is recorded`() {
        val ports = ArrayDeque(listOf(STOP_PORT, NEW_PORT))
        val launched = mutableListOf<List<String>>()
        val probe = PortProbe(STOP_PORT, NEW_PORT)
        val zombie = FakeTunnelProcess.tunnelScript(livePid, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
        val clock = ManualClock()
        val svc =
            service(
                probe = probe,
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { ports.removeFirst() },
                tunnelProcesses = TunnelProcessControl(lookup = { pid -> zombie.handle.takeIf { pid == livePid } }),
                clock = clock,
            )
        svc.ensureRunning(testHost)
        probe.healthy.remove(STOP_PORT)
        clock.advance(PAST_FRESHNESS)

        val second = svc.ensureRunning(testHost)

        assertThat(zombie.handle.isAlive).withFailMessage("the dead tunnel must be stopped, not leaked").isFalse()
        assertThat(dynamicForwardPorts(launched)).containsExactly(STOP_PORT, NEW_PORT)
        assertThat(second.reused).isFalse()
        assertThat(second.localPort).isEqualTo(NEW_PORT)
        assertThat(ProxyEnvFile(tempDir).read().tunnel?.port).isEqualTo(NEW_PORT)
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("$NEW_PORT")
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.UNREACHABLE))
    }

    @Test
    fun `an in-memory reuse within the freshness window does not probe`() {
        val probe = PortProbe(STOP_PORT)
        val clock = ManualClock()
        val svc = inMemoryService(probe, clock, ArrayDeque(listOf(STOP_PORT)))
        svc.ensureRunning(testHost)
        val probesAfterStart = probe.calls.getValue(STOP_PORT)

        clock.advance(Constants.Proxy.REUSE_PROBE_FRESHNESS.minusSeconds(1))
        val second = svc.ensureRunning(testHost)

        assertThat(second.reused).isTrue()
        assertThat(probe.calls.getValue(STOP_PORT)).isEqualTo(probesAfterStart)
    }

    @Test
    fun `an in-memory reuse once the freshness window has passed probes again`() {
        val probe = PortProbe(STOP_PORT)
        val clock = ManualClock()
        val svc = inMemoryService(probe, clock, ArrayDeque(listOf(STOP_PORT)))
        svc.ensureRunning(testHost)
        val probesAfterStart = probe.calls.getValue(STOP_PORT)

        clock.advance(Constants.Proxy.REUSE_PROBE_FRESHNESS)
        svc.ensureRunning(testHost)

        assertThat(probe.calls.getValue(STOP_PORT)).isEqualTo(probesAfterStart + 1)
    }

    @Test
    fun `a probe that passes on reuse starts a new freshness window`() {
        val probe = PortProbe(STOP_PORT)
        val clock = ManualClock()
        val svc = inMemoryService(probe, clock, ArrayDeque(listOf(STOP_PORT)))
        svc.ensureRunning(testHost)
        clock.advance(PAST_FRESHNESS)
        svc.ensureRunning(testHost)
        val probesAfterReuse = probe.calls.getValue(STOP_PORT)

        clock.advance(Duration.ofSeconds(1))
        svc.ensureRunning(testHost)

        assertThat(probe.calls.getValue(STOP_PORT)).isEqualTo(probesAfterReuse)
    }

    @Test
    fun `a replaced tunnel is probed fresh and does not inherit the old tunnel's window`() {
        val probe = PortProbe(STOP_PORT, NEW_PORT)
        val clock = ManualClock()
        val launched = mutableListOf<List<String>>()
        val svc = inMemoryService(probe, clock, ArrayDeque(listOf(STOP_PORT, NEW_PORT)), launched)
        svc.ensureRunning(testHost)
        clock.advance(PAST_FRESHNESS)
        probe.healthy.remove(STOP_PORT)

        val replacement = svc.ensureRunning(testHost)

        assertThat(replacement.localPort).isEqualTo(NEW_PORT)
        assertThat(probe.calls.getValue(NEW_PORT)).withFailMessage("the replacement must be verified by its own probe").isEqualTo(1)
        assertThat(dynamicForwardPorts(launched)).containsExactly(STOP_PORT, NEW_PORT)
    }

    @Test
    fun `a failed probe is never cached, so the next call probes again`() {
        val probe = PortProbe(STOP_PORT)
        val clock = ManualClock()
        var launches = 0
        val svc =
            inMemoryService(
                probe,
                clock,
                ArrayDeque(listOf(STOP_PORT, NEW_PORT, NEW_PORT)),
                launcher = { _, _ -> if (launches++ == 0) liveTunnelProcess() else deadProcess(exitCode = 255) },
                // The tunnel that fails its probe will not stop, so it stays the tunnel the state file names.
                tunnelProcesses = seeing(recordedScript(endsOnSignal = false)),
            )
        svc.ensureRunning(testHost)
        clock.advance(PAST_FRESHNESS)
        probe.healthy.remove(STOP_PORT)
        assertThatThrownBy { svc.ensureRunning(testHost) }.isInstanceOf(IllegalStateException::class.java)
        val probesAfterFailure = probe.calls.getValue(STOP_PORT)
        probe.healthy.add(STOP_PORT)

        clock.advance(Duration.ofSeconds(1))
        val next = svc.ensureRunning(testHost)

        assertThat(probe.calls.getValue(STOP_PORT)).isGreaterThan(probesAfterFailure)
        assertThat(next.localPort).isEqualTo(STOP_PORT)
    }

    @Test
    fun `the state-file path always probes, even within the window of another process`() {
        val probe = PortProbe(STOP_PORT)
        val clock = ManualClock()
        inMemoryService(probe, clock, ArrayDeque(listOf(STOP_PORT))).ensureRunning(testHost)
        val probesAfterStart = probe.calls.getValue(STOP_PORT)

        // A new CLI invocation: a new service that knows the tunnel only from the state file.
        service(probe = probe, tunnelProcesses = seeing(recordedScript()), clock = clock).ensureRunning(testHost)

        assertThat(probe.calls.getValue(STOP_PORT)).isEqualTo(probesAfterStart + 1)
    }

    @Test
    fun `an in-memory tunnel to another gateway is not reused, even within the window`() {
        val probe = PortProbe(STOP_PORT, NEW_PORT)
        val clock = ManualClock()
        val launched = mutableListOf<List<String>>()
        val zombie = FakeTunnelProcess.tunnelScript(livePid, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
        val ports = ArrayDeque(listOf(STOP_PORT, NEW_PORT))
        val svc =
            service(
                probe = probe,
                launcher = { command, _ -> liveTunnelProcess().also { launched += command } },
                portSelector = { ports.removeFirst() },
                tunnelProcesses = TunnelProcessControl(lookup = { pid -> zombie.handle.takeIf { pid == livePid } }),
                clock = clock,
            )
        svc.ensureRunning(testHost)
        val otherGateway = testHost.copy(privateIp = "10.0.1.9")

        val state = svc.ensureRunning(otherGateway)

        assertThat(zombie.handle.isAlive).withFailMessage("the tunnel to the old gateway must be stopped").isFalse()
        assertThat(dynamicForwardPorts(launched)).containsExactly(STOP_PORT, NEW_PORT)
        assertThat(state.reused).isFalse()
        assertThat(state.gatewayHost.privateIp).isEqualTo("10.0.1.9")
        assertThat(ProxyEnvFile(tempDir).read().tunnel?.port).isEqualTo(NEW_PORT)
        assertThat(events).containsExactly(Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.GATEWAY_CHANGED))
    }

    @Test
    fun `a superseded tunnel that will not stop is reported with its PID, and the new tunnel still starts`() {
        writeStateFile(pid = livePid.toInt(), port = STOP_PORT)
        val hung = recordedScript(endsOnSignal = false)

        val state =
            service(
                probe = PortProbe(NEW_PORT),
                launcher = { _, _ -> aliveProcess() },
                portSelector = { NEW_PORT },
                tunnelProcesses = seeing(hung),
            ).ensureRunning(testHost)

        assertThat(state.localPort).isEqualTo(NEW_PORT)
        assertThat(events).containsExactly(
            Event.Proxy.SupersededTunnelStopFailed(pid = livePid.toInt(), port = STOP_PORT),
            Event.Proxy.TunnelReplaced(STOP_PORT, NEW_PORT, Event.Proxy.ReplaceReason.UNREACHABLE),
        )
        assertThat(events.first().toDisplayString()).contains("kill -9 $livePid").contains("pkill -f -- \"-D 127.0.0.1:$STOP_PORT\"")
        assertThat(events.last().toDisplayString()).contains("127.0.0.1:$NEW_PORT").doesNotContain("localhost").contains("browser")
    }

    @Test
    fun `a replacement that fails to start reports no replaced tunnel`() {
        writeStateFile(pid = -1, port = STOP_PORT)

        assertThatThrownBy {
            service(
                launcher = { _, _ -> deadProcess(exitCode = 255) },
                tunnelProcesses = noProcesses,
            ).ensureRunning(testHost)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(events).isEmpty()
    }

    @Test
    fun `a first start and a reuse report no replaced tunnel`() {
        val svc = inMemoryService(PortProbe(STOP_PORT), ManualClock(), ArrayDeque(listOf(STOP_PORT)))

        svc.ensureRunning(testHost)
        svc.ensureRunning(testHost)

        assertThat(events).isEmpty()
    }
}
