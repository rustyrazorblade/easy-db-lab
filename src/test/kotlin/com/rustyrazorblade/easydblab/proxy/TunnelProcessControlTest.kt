package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [TunnelProcessControl] signals a recorded PID only when it is still the recorded `edl-socks-tunnel`,
 * and a stop leaves neither the script nor its `ssh` running.
 */
internal class TunnelProcessControlTest {
    private val recorded =
        Socks5ProxyStateFile(
            pid = PID.toInt(),
            port = PORT,
            controlHost = "control0",
            controlIP = "10.0.1.5",
            clusterName = "lab",
            startTime = "2026-10-09T10:30:00Z",
            sshConfig = SSH_CONFIG,
        )

    private fun control(process: FakeTunnelProcess?) =
        TunnelProcessControl(lookup = { pid -> process?.takeIf { it.pid == pid }?.handle }, stopWait = Duration.ofMillis(50))

    private fun ssh(endsOnSignal: Boolean = true) = FakeTunnelProcess.ssh(SSH_PID, PORT, SSH_CONFIG, endsOnSignal)

    @Test
    fun `stops the recorded tunnel script and its ssh`() {
        val ssh = ssh()
        val tunnel = FakeTunnelProcess.tunnelScript(PID, PORT, SSH_CONFIG, children = listOf(ssh))

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(tunnel.handle.isAlive).isFalse()
        assertThat(ssh.handle.isAlive).isFalse()
    }

    @Test
    fun `a tunnel script that ends only when killed does not leave its ssh running`() {
        val ssh = ssh()
        val tunnel = FakeTunnelProcess.tunnelScript(PID, PORT, SSH_CONFIG, endsOnlyWhenForced = true, children = listOf(ssh))

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(tunnel.handle.isAlive).isFalse()
        assertThat(ssh.handle.isAlive).withFailMessage("the killed script's ssh must be killed too").isFalse()
    }

    @Test
    fun `a tunnel whose ssh will not end is reported as not stopped`() {
        val hungSsh = ssh(endsOnSignal = false)
        val tunnel = FakeTunnelProcess.tunnelScript(PID, PORT, SSH_CONFIG, endsOnlyWhenForced = true, children = listOf(hungSsh))

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(PID.toInt()))
    }

    @Test
    fun `a PID that is gone is not running`() {
        assertThat(control(null).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
    }

    @Test
    fun `a PID now held by another program is left alone`() {
        val other = FakeTunnelProcess(PID, "/bin/sleep", listOf("60"))

        val result = control(other).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(other.signals).isZero()
    }

    @Test
    fun `a shell running another script with the same arguments is left alone`() {
        val other = FakeTunnelProcess(PID, "/bin/sh", listOf("/work/bin/other-script", "$PORT", SSH_CONFIG, "2", "60", "control0"))

        val result = control(other).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(other.signals).isZero()
    }

    @Test
    fun `a tunnel script for another port or sshConfig is left alone`() {
        val otherPort = FakeTunnelProcess.tunnelScript(PID, PORT + 1, SSH_CONFIG)
        val otherConfig = FakeTunnelProcess.tunnelScript(PID, PORT, "/elsewhere/sshConfig")

        assertThat(control(otherPort).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(control(otherConfig).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(otherPort.signals + otherConfig.signals).isZero()
    }

    @Test
    fun `a bare ssh is not the tunnel script and is left alone`() {
        val bare = FakeTunnelProcess.ssh(PID, PORT, SSH_CONFIG)

        assertThat(control(bare).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(bare.signals).isZero()
    }

    @Test
    fun `a shell whose arguments the OS does not report is left alone`() {
        val unknown = FakeTunnelProcess(PID, "/bin/sh", arguments = null)

        assertThat(control(unknown).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(unknown.signals).isZero()
    }

    @Test
    fun `a tunnel script that will not end is reported after a gentle and a forced signal`() {
        val hung = FakeTunnelProcess.tunnelScript(PID, PORT, SSH_CONFIG, endsOnSignal = false)

        val result = control(hung).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(PID.toInt()))
        assertThat(hung.signals).isEqualTo(2)
    }

    @Test
    fun `a real process that is not the tunnel script is not signaled`() {
        val sleeper = ProcessBuilder("sleep", "60").start()
        try {
            val result = TunnelProcessControl().stop(recorded.copy(pid = sleeper.pid().toInt()))

            assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
            assertThat(sleeper.isAlive).isTrue()
        } finally {
            sleeper.destroyForcibly()
        }
    }

    private companion object {
        const val PID = 4242L
        const val SSH_PID = 4243L
        const val PORT = 41234
        const val SSH_CONFIG = "/work/sshConfig"
    }
}
