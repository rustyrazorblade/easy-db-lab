package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** [TunnelProcessControl] signals a recorded PID only when it is still the recorded tunnel. */
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

    @Test
    fun `stops the recorded tunnel`() {
        val tunnel = FakeTunnelProcess.sshTunnel(PID, PORT, SSH_CONFIG)

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(tunnel.handle.isAlive).isFalse()
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
    fun `an ssh that is another tunnel is left alone`() {
        val otherTunnel = FakeTunnelProcess.sshTunnel(PID, PORT + 1, "/elsewhere/sshConfig")

        val result = control(otherTunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(otherTunnel.signals).isZero()
    }

    @Test
    fun `an ssh whose arguments the OS does not report is taken as the tunnel`() {
        val tunnel = FakeTunnelProcess(PID, "/usr/bin/ssh", arguments = null)

        assertThat(control(tunnel).stop(recorded)).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
    }

    @Test
    fun `a tunnel that will not end is reported after a gentle and a forced signal`() {
        val hung = FakeTunnelProcess.sshTunnel(PID, PORT, SSH_CONFIG, endsOnSignal = false)

        val result = control(hung).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(PID.toInt()))
        assertThat(hung.signals).isEqualTo(2)
    }

    @Test
    fun `a real process that is not ssh is not signaled`() {
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
        const val PORT = 41234
        const val SSH_CONFIG = "/work/sshConfig"
    }
}
