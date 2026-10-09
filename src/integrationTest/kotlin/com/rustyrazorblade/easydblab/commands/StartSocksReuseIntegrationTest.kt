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
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SshProcessLauncher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File
import java.net.ServerSocket

/**
 * `start-socks` with a verified tunnel already running keeps it. Reuse needs a port that really
 * accepts connections, which is why this case lives in the integration tier.
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class StartSocksReuseIntegrationTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single { ProxyEnvFile(get<Context>().workingDirectory) }
                single<SocksProxyService> {
                    ProcessSocksProxyService(
                        get(),
                        { _, _, _ -> true },
                        processLauncher = SshProcessLauncher { _, _ -> error("a running tunnel must be reused, not replaced") },
                        envFile = get(),
                    )
                }
            },
        )

    @AfterEach
    fun clearPort() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `a running tunnel is reused, and its port is recorded and reported`() {
        val workspace = context.workingDirectory
        val sshConfig = File(workspace, "sshConfig").apply { writeText("Host control0\n  Hostname 10.0.1.5\n") }
        get<ClusterStateManager>().save(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.1.2.3", "10.0.1.5", "control0", "us-west-2a", "i-ctrl"))),
            ).apply { markInfrastructureUp() },
        )
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )

        ServerSocket(0).use { listener ->
            val port = listener.localPort
            File(workspace, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).writeText(
                """
                {
                  "pid": ${ProcessHandle.current().pid()},
                  "port": $port,
                  "controlHost": "control0",
                  "controlIP": "10.0.1.5",
                  "clusterName": "test",
                  "startTime": "2026-10-09T10:30:00Z",
                  "sshConfig": "${sshConfig.absolutePath}"
                }
                """.trimIndent(),
            )

            StartSocks().call()

            assertThat(ProxyEnvFile(workspace).read().socksPort).isEqualTo(port)
            assertThat(events).containsExactly(Event.Proxy.TunnelReady(port = port, reused = true))
        }
    }
}
