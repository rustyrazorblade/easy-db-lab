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

    /** A control whose lookup finds only [process], and whose process list holds only [running]. */
    private fun control(
        process: FakeTunnelProcess?,
        running: List<FakeTunnelProcess> = listOfNotNull(process),
    ) = TunnelProcessControl(
        lookup = { pid -> process?.takeIf { it.pid == pid }?.handle },
        allProcesses = { running.map { it.handle }.stream() },
        stopWait = Duration.ofMillis(50),
    )

    private fun ssh(endsOnSignal: Boolean = true) = FakeTunnelProcess.ssh(SSH_PID, PORT, SSH_CONFIG, endsOnSignal)

    @Test
    fun `an ssh the loop starts while TERM goes unanswered is ended too, and the stop succeeds`() {
        val ssh = ssh()
        val restarted = FakeTunnelProcess.ssh(SSH_PID + 1, PORT, SSH_CONFIG)
        val tunnel =
            FakeTunnelProcess.tunnelScript(
                PID,
                PORT,
                SSH_CONFIG,
                endsOnlyWhenForced = true,
                children = listOf(ssh),
                startsOnIgnoredTerm = restarted,
            )

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(restarted.signals).withFailMessage("the ssh started during the ignored TERM was never signaled").isPositive()
        assertThat(restarted.handle.isAlive).withFailMessage("the ssh started during the ignored TERM was left running").isFalse()
        assertThat(ssh.handle.isAlive).isFalse()
    }

    @Test
    fun `the orphaned ssh of a killed script is found by its port and sshConfig and stopped`() {
        val orphan = ssh()
        val otherPort = FakeTunnelProcess.ssh(SSH_PID + 1, PORT + 1, SSH_CONFIG)
        val otherConfig = FakeTunnelProcess.ssh(SSH_PID + 2, PORT, "/elsewhere/sshConfig")

        val result = control(process = null, running = listOf(orphan, otherPort, otherConfig)).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(orphan.handle.isAlive).isFalse()
        assertThat(otherPort.signals + otherConfig.signals).isZero()
    }

    @Test
    fun `the orphaned ssh is stopped when the script's PID now belongs to another program`() {
        val other = FakeTunnelProcess(PID, "/bin/sleep", listOf("60"))
        val orphan = ssh()

        val result = control(other, running = listOf(other, orphan)).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.Stopped(PID.toInt()))
        assertThat(orphan.handle.isAlive).isFalse()
        assertThat(other.signals).isZero()
    }

    @Test
    fun `an orphaned ssh that will not end is reported with its own PID`() {
        val hung = ssh(endsOnSignal = false)

        val result = control(process = null, running = listOf(hung)).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(SSH_PID.toInt()))
        assertThat(hung.signals).isEqualTo(2)
    }

    @Test
    fun `a process that only names the port and sshConfig is not taken for the tunnel's ssh`() {
        val notSsh = FakeTunnelProcess(SSH_PID, "/usr/bin/nc", listOf("-D", "$PORT", "-F", SSH_CONFIG))
        val unreported = FakeTunnelProcess(SSH_PID + 1, "/usr/bin/ssh", arguments = null)

        val result = control(process = null, running = listOf(notSsh, unreported)).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(notSsh.signals + unreported.signals).isZero()
    }

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
    fun `a tunnel whose ssh will not end is reported as not stopped, by the ssh's PID, the one left to kill`() {
        val hungSsh = ssh(endsOnSignal = false)
        val tunnel = FakeTunnelProcess.tunnelScript(PID, PORT, SSH_CONFIG, endsOnlyWhenForced = true, children = listOf(hungSsh))

        val result = control(tunnel).stop(recorded)

        assertThat(result).isEqualTo(TunnelStopResult.StopFailed(SSH_PID.toInt()))
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
        val other =
            FakeTunnelProcess(
                PID,
                "/bin/sh",
                listOf("/work/bin/other-script", "$PORT", SSH_CONFIG, "2", "/work/logs/socks5-proxy.log", "control0"),
            )

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
    fun `an ssh whose tunnel script still runs is neither the recorded script nor an orphan, and is left alone`() {
        val bare = FakeTunnelProcess.ssh(PID, PORT, SSH_CONFIG)
        val itsScript = FakeTunnelProcess.tunnelScript(SSH_PID, PORT, SSH_CONFIG, children = listOf(bare))

        assertThat(control(bare, running = listOf(bare, itsScript)).stop(recorded)).isEqualTo(TunnelStopResult.NotRunning)
        assertThat(bare.signals + itsScript.signals).isZero()
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
