package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.FakeTunnelProcess
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnv
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.TunnelProcessControl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File
import java.time.Duration

/**
 * `stop-socks` ends the tunnel recorded in the workspace and leaves the cluster alone. The process
 * lookup sees only [process], because a test cannot start a real `ssh -N -D`.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class StopSocksTest : BaseKoinTest() {
    private val events = mutableListOf<Event>()
    private var process: FakeTunnelProcess? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ProxyEnvFile(get<Context>().workingDirectory) }
                single<SocksProxyService> {
                    ProcessSocksProxyService(
                        get(),
                        { _, _, _ -> false },
                        envFile = get(),
                        tunnelProcesses =
                            TunnelProcessControl(
                                lookup = { pid -> process?.takeIf { it.pid == pid }?.handle },
                                stopWait = Duration.ofMillis(50),
                            ),
                    )
                }
            },
        )

    @BeforeEach
    fun captureEvents() {
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    @AfterEach
    fun clearPort() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `stops the tunnel and removes its port, keeping the Tailscale flag`() {
        val tunnel = FakeTunnelProcess.sshTunnel(PID, PORT, sshConfig()).also { process = it }
        val stateFile = recordTunnel()
        val envFile = recordedEnv()
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "$PORT")

        StopSocks().call()

        assertThat(tunnel.handle.isAlive).isFalse()
        assertThat(stateFile).doesNotExist()
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
        assertThat(events).containsExactly(Event.Proxy.TunnelStopped(PID.toInt()))
    }

    @Test
    fun `a tunnel that will not stop fails the command and stays recorded`() {
        process = FakeTunnelProcess.sshTunnel(PID, PORT, sshConfig(), endsOnSignal = false)
        val stateFile = recordTunnel()
        val envFile = recordedEnv()

        assertThatThrownBy { StopSocks().call() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(stateFile).exists()
        assertThat(envFile.read().socksPort).isEqualTo(PORT)
        assertThat(events).containsExactly(Event.Proxy.TunnelStopFailed(PID.toInt()))
    }

    @Test
    fun `a recorded PID that is gone is reported as no tunnel, and the stale record goes`() {
        val stateFile = recordTunnel()
        val envFile = recordedEnv()

        StopSocks().call()

        assertThat(stateFile).doesNotExist()
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
        assertThat(events).containsExactly(Event.Proxy.NoTunnelRunning)
    }

    @Test
    fun `with nothing recorded it says so and still removes a stale port`() {
        val envFile = recordedEnv()

        StopSocks().call()

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
        assertThat(events).containsExactly(Event.Proxy.NoTunnelRunning)
    }

    private fun sshConfig() = File(context.workingDirectory, "sshConfig").absolutePath

    private fun recordedEnv() =
        ProxyEnvFile(context.workingDirectory).apply {
            recordTailscale(active = false)
            recordPort(PORT)
        }

    private fun recordTunnel(): File =
        File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).apply {
            writeText(
                """
                {
                  "pid": $PID,
                  "port": $PORT,
                  "controlHost": "control0",
                  "controlIP": "10.0.1.5",
                  "clusterName": "test",
                  "startTime": "2026-10-09T10:30:00Z",
                  "sshConfig": "${sshConfig()}"
                }
                """.trimIndent(),
            )
        }

    private companion object {
        const val PID = 4242L
        const val PORT = 41234
    }
}
