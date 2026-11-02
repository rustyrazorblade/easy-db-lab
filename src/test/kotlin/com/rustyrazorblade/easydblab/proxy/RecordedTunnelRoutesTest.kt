package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.util.stream.Stream

class RecordedTunnelRoutesTest : BaseKoinTest() {
    private val workspace: File get() = tempDir
    private val sshConfig: String get() = File(workspace, "sshConfig").absolutePath
    private val envFile: ProxyEnvFile get() = ProxyEnvFile(workspace)
    private val state = ClusterState(name = "lab", versions = mutableMapOf())

    private fun routes(vararg processes: FakeTunnelProcess): RecordedTunnelRoutes =
        RecordedTunnelRoutes(
            envFile = envFile,
            sshConfig = sshConfig,
            control =
                TunnelProcessControl(
                    lookup = { pid -> processes.firstOrNull { it.pid == pid }?.handle },
                    allProcesses = { Stream.empty() },
                    stopWait = Duration.ofMillis(50),
                ),
        )

    @Test
    fun `Tailscale gives a direct route and reads no tunnel`() {
        state.tailscaleActive = true

        assertThat(routes().current(state)).isEqualTo(RouteLookup.Usable(ObservabilityRoute.Direct))
    }

    @Test
    fun `a live recorded tunnel gives its port`() {
        envFile.recordTunnel(PORT, PID)

        assertThat(routes(FakeTunnelProcess.tunnelScript(PID, PORT, sshConfig)).current(state))
            .isEqualTo(RouteLookup.Usable(ObservabilityRoute.Socks(PORT)))
    }

    @Test
    fun `no recorded tunnel says to run socks start`() {
        assertSaysSocksStart(routes().current(state))
    }

    @Test
    fun `a recorded tunnel whose process has exited says to run socks start`() {
        envFile.recordTunnel(PORT, PID)

        assertSaysSocksStart(routes().current(state))
    }

    @Test
    fun `a recorded PID now held by another program says to run socks start`() {
        envFile.recordTunnel(PORT, PID)

        assertSaysSocksStart(routes(FakeTunnelProcess(PID, "/bin/sleep", listOf("60"))).current(state))
    }

    @Test
    fun `a new tunnel recorded on another port is used on the next lookup`() {
        val routes =
            routes(
                FakeTunnelProcess.tunnelScript(PID, PORT, sshConfig),
                FakeTunnelProcess.tunnelScript(PID + 1, PORT + 1, sshConfig),
            )
        envFile.recordTunnel(PORT, PID)
        assertThat(routes.current(state)).isEqualTo(RouteLookup.Usable(ObservabilityRoute.Socks(PORT)))

        envFile.recordTunnel(PORT + 1, PID + 1)

        assertThat(routes.current(state)).isEqualTo(RouteLookup.Usable(ObservabilityRoute.Socks(PORT + 1)))
    }

    private fun assertSaysSocksStart(lookup: RouteLookup) {
        assertThat(lookup).isInstanceOf(RouteLookup.Unavailable::class.java)
        assertThat((lookup as RouteLookup.Unavailable).reason).contains("socks start")
    }

    private companion object {
        const val PID = 5151L
        const val PORT = 21234
    }
}
