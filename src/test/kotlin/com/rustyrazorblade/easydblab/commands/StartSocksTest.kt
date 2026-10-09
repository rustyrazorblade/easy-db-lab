package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnv
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SshProcessLauncher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.time.Duration

/**
 * `start-socks` through the real [ProcessSocksProxyService], with only the `ssh` process and the
 * end-to-end probe faked, so the state file and the env file it writes are the real ones.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class StartSocksTest : BaseKoinTest() {
    private val launched = mutableListOf<List<String>>()
    private val events = mutableListOf<Event>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single { ProxyEnvFile(get<Context>().workingDirectory) }
                single<SocksProxyService> {
                    ProcessSocksProxyService(
                        get(),
                        { _, _, _ -> true },
                        verifyDelay = Duration.ofMillis(1),
                        processLauncher =
                            SshProcessLauncher { command, _ ->
                                launched += command
                                mock {
                                    on { isAlive } doReturn true
                                    on { pid() } doReturn FAKE_PID
                                }
                            },
                        portSelector = { PORT },
                        envFile = get(),
                    )
                }
            },
        )

    @BeforeEach
    fun captureEvents() {
        File(context.workingDirectory, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        save(clusterState(tailscale = false))
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
    fun `starts the tunnel, records its port, and reports it`() {
        StartSocks().call()

        assertThat(launched).hasSize(1)
        assertThat(ProxyEnvFile(context.workingDirectory).read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = PORT))
        assertThat(events).containsExactly(Event.Proxy.TunnelReady(port = PORT, reused = false))
        assertThat(events.single().toDisplayString()).contains("localhost:$PORT")
    }

    @Test
    fun `on a Tailscale cluster it starts nothing and says no tunnel is needed`() {
        save(clusterState(tailscale = true))

        StartSocks().call()

        assertThat(launched).isEmpty()
        assertThat(ProxyEnvFile(context.workingDirectory).read()).isEqualTo(ProxyEnv(tailscaleActive = true, socksPort = null))
        assertThat(events).containsExactly(Event.Proxy.TunnelNotNeeded)
    }

    @Test
    fun `with no running cluster it fails and starts nothing`() {
        save(clusterState(tailscale = false).apply { markInfrastructureDown() })

        assertThatThrownBy { StartSocks().call() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(launched).isEmpty()
        assertThat(events).containsExactly(Event.Proxy.NoRunningCluster(context.workingDirectory.absolutePath))
    }

    private fun save(state: ClusterState) = get<ClusterStateManager>().save(state)

    private fun clusterState(tailscale: Boolean): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.1.2.3", "10.0.1.5", "control0", "us-west-2a", "i-ctrl"))),
            tailscaleActive = tailscale,
        ).apply { markInfrastructureUp() }

    private companion object {
        const val PORT = 41234
        const val FAKE_PID = 4242L
    }
}
