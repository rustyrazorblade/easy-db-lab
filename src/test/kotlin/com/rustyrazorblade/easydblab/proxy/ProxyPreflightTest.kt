package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Instant
import kotlin.concurrent.thread

/**
 * [ProxyPreflight] decides, before a `@RequiresProxy` command, whether to start the tunnel, and
 * records the Tailscale flag for shell-side tools whenever the cluster state says what it is.
 * The proxy service is mocked because it launches `ssh`.
 */
internal class ProxyPreflightTest {
    @TempDir
    lateinit var workspace: File

    private val socksProxyService = mock<SocksProxyService>()
    private val stateManager by lazy { ClusterStateManager(File(workspace, "state.json")) }
    private val envFile by lazy { ProxyEnvFile(workspace) }
    private val preflight by lazy { ProxyPreflight(stateManager, socksProxyService, envFile) }

    @Test
    fun `a running SOCKS cluster gets its tunnel and the env file says it is not Tailscale`() {
        stateManager.save(clusterState(tailscale = false))

        preflight.ensureTunnel()

        verify(socksProxyService).ensureRunning(CONTROL)
        assertThat(envFile.read().tailscaleActive).isFalse()
    }

    @Test
    fun `a Tailscale cluster starts no tunnel and the env file says it is Tailscale`() {
        stateManager.save(clusterState(tailscale = true))

        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.read()).isEqualTo(ProxyEnv(tailscaleActive = true, tunnel = null))
    }

    @Test
    fun `a cluster that is not up starts no tunnel but still records the flag`() {
        stateManager.save(clusterState(tailscale = true).apply { markInfrastructureDown() })

        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.read().tailscaleActive).isTrue()
    }

    @Test
    fun `a directory with no cluster state starts nothing and writes nothing`() {
        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.file).doesNotExist()
    }

    @Test
    fun `the tunnel it started or reused is recorded for the command, and clear removes it`() {
        stateManager.save(clusterState(tailscale = false))
        val started = SocksProxyState(localPort = 41234, gatewayHost = CONTROL, startTime = Instant.EPOCH, reused = false)
        whenever(socksProxyService.ensureRunning(CONTROL)).thenReturn(started)

        preflight.ensureTunnel()

        assertThat(preflight.tunnelForCommand()).isEqualTo(started)
        preflight.clear()
        assertThat(preflight.tunnelForCommand()).isNull()
    }

    @Test
    fun `a Tailscale cluster records no tunnel for the command`() {
        stateManager.save(clusterState(tailscale = true))

        preflight.ensureTunnel()

        assertThat(preflight.tunnelForCommand()).isNull()
    }

    @Test
    fun `a tunnel recorded on another thread is not this thread's`() {
        stateManager.save(clusterState(tailscale = false))
        whenever(socksProxyService.ensureRunning(CONTROL))
            .thenReturn(SocksProxyState(localPort = 41234, gatewayHost = CONTROL, startTime = Instant.EPOCH))
        thread { preflight.ensureTunnel() }.join()

        assertThat(preflight.tunnelForCommand()).isNull()
    }

    private fun clusterState(tailscale: Boolean) =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            hosts = mapOf(ServerType.Control to listOf(CONTROL)),
            tailscaleActive = tailscale,
        ).apply { markInfrastructureUp() }

    private companion object {
        val CONTROL = ClusterHost("54.1.2.3", "10.0.1.5", "control0", "us-west-2a", "i-ctrl")
    }
}
