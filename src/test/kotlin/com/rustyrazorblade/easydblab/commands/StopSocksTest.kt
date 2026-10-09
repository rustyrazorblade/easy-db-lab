package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnv
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File
import java.util.concurrent.TimeUnit

/** `stop-socks` ends the tunnel recorded in the workspace and leaves the cluster alone. */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class StopSocksTest : BaseKoinTest() {
    private val events = mutableListOf<Event>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ProxyEnvFile(get<Context>().workingDirectory) }
                single<SocksProxyService> { ProcessSocksProxyService(get(), { _, _, _ -> false }, envFile = get()) }
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
        // A real, killable stand-in for the `ssh -N -D` tunnel process.
        val tunnel = ProcessBuilder("sleep", "60").start()
        try {
            val stateFile = recordTunnel(tunnel.pid())
            val envFile =
                ProxyEnvFile(context.workingDirectory).apply {
                    recordTailscale(active = false)
                    recordPort(PORT)
                }
            System.setProperty(Constants.Proxy.PORT_PROPERTY, "$PORT")

            StopSocks().call()

            assertThat(tunnel.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).withFailMessage("the tunnel was not stopped").isTrue()
            assertThat(stateFile).doesNotExist()
            assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
            assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
            assertThat(events).containsExactly(Event.Proxy.TunnelStopped(tunnel.pid().toInt()))
        } finally {
            tunnel.destroyForcibly()
        }
    }

    @Test
    fun `with no tunnel running it says so and still removes a stale port`() {
        val envFile =
            ProxyEnvFile(context.workingDirectory).apply {
                recordTailscale(active = false)
                recordPort(PORT)
            }

        StopSocks().call()

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
        assertThat(events).containsExactly(Event.Proxy.NoTunnelRunning)
    }

    private fun recordTunnel(pid: Long): File =
        File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).apply {
            writeText(
                """
                {
                  "pid": $pid,
                  "port": $PORT,
                  "controlHost": "control0",
                  "controlIP": "10.0.1.5",
                  "clusterName": "test",
                  "startTime": "2026-10-09T10:30:00Z",
                  "sshConfig": "${context.workingDirectory}/sshConfig"
                }
                """.trimIndent(),
            )
        }

    private companion object {
        const val PORT = 41234
        const val LIMIT_SECONDS = 5L
    }
}
