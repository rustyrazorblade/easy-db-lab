package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.times
import java.io.File

/** [ProcessSocksProxyService] stopping a tunnel: `stop()` for `stop-socks` and `down`, and the stop of a superseded tunnel before its record is overwritten. */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
internal class ProcessSocksProxyStopTest : ProcessSocksProxyServiceTestBase() {
    @Test
    fun `stop ends the recorded tunnel and removes the state file and the port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val tunnel = FakeTunnelProcess.tunnelScript(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)

        val result = serviceSeeing(tunnel).stop()

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(FAKE_PID.toInt()))
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = null))
    }

    @Test
    fun `stop with a dead PID reports no tunnel and removes the state file and the port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()

        val result = serviceSeeing(null).stop()

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read().tunnel).isNull()
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
        assertThat(envFile.read().tunnel).isNull()
    }

    @Test
    fun `a tunnel that will not stop keeps its state file and its port`() {
        writeStateFile(pid = FAKE_PID.toInt(), port = STOP_PORT)
        val envFile = recordedEnv()
        val hung = FakeTunnelProcess.tunnelScript(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath, endsOnSignal = false)

        val result = serviceSeeing(hung).stop()

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(FAKE_PID.toInt()))
        assertThat(stateFile()).exists()
        assertThat(envFile.read().tunnel?.port).isEqualTo(STOP_PORT)
    }

    @Test
    fun `stop with a corrupt state file removes it and the port`() {
        stateFile().writeText("{ not json")
        val envFile = recordedEnv()

        val result = serviceSeeing(null).stop()

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(stateFile()).doesNotExist()
        assertThat(envFile.read().tunnel).isNull()
    }

    @Test
    fun `a superseded tunnel that is still our ssh is terminated before its record is overwritten`() {
        val zombie = FakeTunnelProcess.tunnelScript(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)

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
        val tunnel = FakeTunnelProcess.tunnelScript(FAKE_PID, STOP_PORT, File(tempDir, "sshConfig").absolutePath)
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
}
