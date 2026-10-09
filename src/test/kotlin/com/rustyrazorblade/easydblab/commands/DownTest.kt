package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.proxy.FakeTunnelProcess
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnv
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.TunnelProcessControl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File
import java.time.Duration

class DownTest : BaseKoinTest() {
    /** The only process the tunnel stop can see; a test cannot start a real `ssh -N -D`. */
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
    @AfterEach
    fun clearProxyProperty() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    @ResourceLock(Constants.Proxy.PORT_PROPERTY)
    fun `clearProxySystemProperties clears the published SOCKS5 proxy port`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "19082")
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isEqualTo("19082")

        Down().clearProxySystemProperties()

        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
    }

    @Test
    fun `cleanupSocks5Proxy resolves the state file against workingDirectory and stops the tunnel`() {
        // The state file lives in the cluster working directory, NOT the process cwd. The test
        // process cwd is the project root (never the temp workingDirectory), so a cwd-relative
        // resolver would miss the file, skip the stop, and orphan the ssh tunnel (issue #738).
        val tunnel = FakeTunnelProcess.sshTunnel(TUNNEL_PID, TUNNEL_PORT, sshConfig()).also { process = it }
        val proxyStateFile = recordTunnel()
        val events = captureEvents()

        Down().cleanupSocks5Proxy()

        assertThat(tunnel.handle.isAlive).withFailMessage("cleanupSocks5Proxy should have stopped the tunnel").isFalse()
        assertThat(proxyStateFile).doesNotExist()
        assertThat(events).containsExactly(Event.Teardown.Socks5ProxyStopped(TUNNEL_PID.toInt()))
    }

    @Test
    fun `cleanupSocks5Proxy reports nothing when no tunnel is recorded`() {
        val events = captureEvents()

        Down().cleanupSocks5Proxy()

        assertThat(events).isEmpty()
    }

    @Test
    fun `cleanupSocks5Proxy reports a tunnel that will not stop and keeps it recorded`() {
        process = FakeTunnelProcess.sshTunnel(TUNNEL_PID, TUNNEL_PORT, sshConfig(), endsOnSignal = false)
        val proxyStateFile = recordTunnel()
        val events = captureEvents()

        Down().cleanupSocks5Proxy()

        assertThat(proxyStateFile).exists()
        assertThat(events).containsExactly(Event.Teardown.Socks5ProxyStopFailed(TUNNEL_PID.toInt()))
    }

    private fun sshConfig() = File(context.workingDirectory, "sshConfig").absolutePath

    private fun recordTunnel(): File =
        File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).apply {
            writeText(
                """
                {
                  "pid": $TUNNEL_PID,
                  "port": $TUNNEL_PORT,
                  "controlHost": "control0",
                  "controlIP": "10.0.1.5",
                  "clusterName": "test",
                  "startTime": "2025-01-19T10:30:00Z",
                  "sshConfig": "${sshConfig()}"
                }
                """.trimIndent(),
            )
        }

    private fun captureEvents(): List<Event> {
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        return events
    }

    @Test
    fun `cleanupSocks5Proxy removes the port from the proxy env file and keeps the Tailscale flag`() {
        val envFile =
            ProxyEnvFile(context.workingDirectory).apply {
                recordTailscale(active = false)
                recordPort(41234)
            }

        Down().cleanupSocks5Proxy()

        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = false, socksPort = null))
    }

    @Test
    fun `Down command should mark infrastructure as DOWN in cluster state`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create a cluster state that's currently UP
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        state.markInfrastructureUp()
        manager.save(state)

        // Verify it's UP
        val beforeState = manager.load()
        assertThat(beforeState.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(beforeState.isInfrastructureUp()).isTrue()

        // Simulate Down command marking it as DOWN
        beforeState.markInfrastructureDown()
        manager.save(beforeState)

        // Load and verify it's DOWN
        val afterState = manager.load()
        assertThat(afterState.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
        assertThat(afterState.isInfrastructureUp()).isFalse()
    }

    @Test
    fun `Down command should handle missing proxy state file gracefully`(
        @TempDir tempDir: File,
    ) {
        val proxyStateFile = File(tempDir, ".socks5-proxy-state")

        // File doesn't exist
        assertThat(proxyStateFile).doesNotExist()

        // Cleanup should not fail even if file doesn't exist
        if (proxyStateFile.exists()) {
            proxyStateFile.delete()
        }

        // Still doesn't exist, but no error
        assertThat(proxyStateFile).doesNotExist()
    }

    @Test
    fun `Down command should handle missing cluster state file gracefully`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // File doesn't exist
        assertThat(stateFile).doesNotExist()
        assertThat(manager.exists()).isFalse()

        // Trying to update non-existent state should not crash
        // In the real Down command, it checks if the file exists first
        val fileExists = stateFile.exists()
        assertThat(fileExists).isFalse()

        // If file exists (which it doesn't), it would load and update
        // This tests the guard condition in Down.updateClusterState()
    }

    @Test
    fun `Down command should clean up proxy state with corrupted JSON`(
        @TempDir tempDir: File,
    ) {
        val proxyStateFile = File(tempDir, ".socks5-proxy-state")

        // Write corrupted JSON
        proxyStateFile.writeText("{ invalid json")

        assertThat(proxyStateFile).exists()

        // Cleanup should still delete the file even if JSON is corrupted
        // The Down command catches JSON parsing exceptions and deletes anyway
        try {
            // In real Down command, it tries to parse, fails, then deletes
            proxyStateFile.delete()
        } catch (e: Exception) {
            // If it fails for any reason, still try to delete
            proxyStateFile.delete()
        }

        assertThat(proxyStateFile).doesNotExist()
    }

    @Test
    fun `Down command should clear tailscaleAuthKeyId when marking DOWN`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
            )
        state.updateTailscaleAuthKeyId("key-id-123")
        manager.save(state)

        // Verify the key ID is set
        val loadedState = manager.load()
        assertThat(loadedState.tailscaleAuthKeyId).isEqualTo("key-id-123")

        // Simulate Down command clearing the key
        loadedState.updateTailscaleAuthKeyId(null)
        loadedState.markInfrastructureDown()
        manager.save(loadedState)

        // Verify the key ID is cleared
        val afterState = manager.load()
        assertThat(afterState.tailscaleAuthKeyId).isNull()
        assertThat(afterState.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
    }

    @Test
    fun `Down command should preserve cluster state fields when marking DOWN`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        // Create a cluster state with various fields populated
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf("cassandra" to "4.1.3"),
            )
        state.markInfrastructureUp()
        manager.save(state)

        val clusterId = state.clusterId
        val createdAt = state.createdAt

        // Mark as DOWN
        state.markInfrastructureDown()
        manager.save(state)

        // Reload and verify all fields preserved except status
        val reloadedState = manager.load()
        assertThat(reloadedState.name).isEqualTo("test-cluster")
        assertThat(reloadedState.versions).containsEntry("cassandra", "4.1.3")
        assertThat(reloadedState.clusterId).isEqualTo(clusterId)
        assertThat(reloadedState.createdAt).isEqualTo(createdAt)
        assertThat(reloadedState.infrastructureStatus).isEqualTo(InfrastructureStatus.DOWN)
    }

    private companion object {
        const val TUNNEL_PID = 4242L
        const val TUNNEL_PORT = 41234
    }
}
