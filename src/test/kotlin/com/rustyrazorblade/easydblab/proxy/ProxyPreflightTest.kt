package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NetworkMode
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
 * records the network mode for shell-side tools whenever the cluster state says what it is.
 * The proxy service is mocked because it launches `ssh`.
 */
internal class ProxyPreflightTest {
    @TempDir
    lateinit var workspace: File

    private val socksProxyService = mock<SocksProxyService>()
    private val stateManager by lazy { ClusterStateManager(File(workspace, "state.json")) }
    private val envFile by lazy { ProxyEnvFile(workspace) }
    private val preflight by lazy {
        ProxyPreflight(stateManager, socksProxyService, WorkspaceShellTools(ToolWrapperInstaller()), workspace)
    }

    @Test
    fun `a running SOCKS cluster gets its tunnel and the env file records socks`() {
        stateManager.save(clusterState(NetworkMode.Socks))

        preflight.ensureTunnel()

        verify(socksProxyService).ensureRunning(CONTROL)
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Socks)
    }

    @Test
    fun `a Tailscale cluster starts no tunnel and the env file records tailscale`() {
        stateManager.save(clusterState(NetworkMode.Tailscale))

        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Tailscale, tunnel = null))
    }

    @Test
    fun `a direct cluster starts no tunnel and the env file records direct`() {
        stateManager.save(clusterState(NetworkMode.Direct))

        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(preflight.tunnelForCommand()).isNull()
        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Direct, tunnel = null))
    }

    @Test
    fun `a mode that needs no tunnel leaves a recorded tunnel recorded`() {
        envFile.recordTunnel(41234, 4242)
        stateManager.save(clusterState(NetworkMode.Direct))

        preflight.ensureTunnel()

        assertThat(envFile.read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Direct, tunnel = RecordedTunnel(41234, 4242)))
    }

    @Test
    fun `a cluster that is not up starts no tunnel but still records the mode`() {
        stateManager.save(clusterState(NetworkMode.Tailscale).apply { markInfrastructureDown() })

        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Tailscale)
    }

    @Test
    fun `an earlier release's workspace gets the packaged wrappers and a converted env file`() {
        val bin = File(workspace, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
        File(bin, Constants.ToolWrappers.MARKER).writeText("")
        val oldWrapper = oldWrapperScript()
        Constants.ToolWrappers.WRAPPED_TOOLS.forEach { File(bin, it).writeText(oldWrapper) }
        envFile.file.writeText("${Constants.Proxy.ENV_TAILSCALE_ACTIVE}=true\n")
        stateManager.save(clusterState(NetworkMode.Tailscale))

        preflight.ensureTunnel()

        val packaged = packagedWrapperScript()
        Constants.ToolWrappers.WRAPPED_TOOLS.forEach { assertThat(File(bin, it)).hasContent(packaged) }
        assertThat(envFile.file).hasContent("${Constants.Proxy.ENV_NETWORK_MODE}=tailscale")
    }

    @Test
    fun `a directory with no cluster state starts nothing and writes nothing`() {
        preflight.ensureTunnel()

        verify(socksProxyService, never()).ensureRunning(any())
        assertThat(envFile.file).doesNotExist()
    }

    @Test
    fun `the tunnel it started or reused is recorded for the command, and clear removes it`() {
        stateManager.save(clusterState(NetworkMode.Socks))
        val started = SocksProxyState(localPort = 41234, gatewayHost = CONTROL, startTime = Instant.EPOCH, reused = false)
        whenever(socksProxyService.ensureRunning(CONTROL)).thenReturn(started)

        preflight.ensureTunnel()

        assertThat(preflight.tunnelForCommand()).isEqualTo(started)
        preflight.clear()
        assertThat(preflight.tunnelForCommand()).isNull()
    }

    @Test
    fun `a Tailscale cluster records no tunnel for the command`() {
        stateManager.save(clusterState(NetworkMode.Tailscale))

        preflight.ensureTunnel()

        assertThat(preflight.tunnelForCommand()).isNull()
    }

    @Test
    fun `a tunnel recorded on another thread is not this thread's`() {
        stateManager.save(clusterState(NetworkMode.Socks))
        whenever(socksProxyService.ensureRunning(CONTROL))
            .thenReturn(SocksProxyState(localPort = 41234, gatewayHost = CONTROL, startTime = Instant.EPOCH))
        thread { preflight.ensureTunnel() }.join()

        assertThat(preflight.tunnelForCommand()).isNull()
    }

    private fun clusterState(mode: NetworkMode) =
        ClusterState(
            name = "test-cluster",
            hosts = mapOf(ServerType.Control to listOf(CONTROL)),
            networkMode = mode,
        ).apply { markInfrastructureUp() }

    private fun oldWrapperScript(): String =
        checkNotNull(javaClass.getResource(OLD_WRAPPER_FIXTURE)) { "missing $OLD_WRAPPER_FIXTURE" }.readText()

    private fun packagedWrapperScript(): String =
        checkNotNull(javaClass.getResource(Constants.ToolWrappers.RESOURCE)) { "missing packaged wrapper" }.readText()

    private companion object {
        val CONTROL = ClusterHost("54.1.2.3", "10.0.1.5", "control0", "us-west-2a", "i-ctrl")

        /** The tool wrapper as `main` shipped it before the network mode, which knows only EDL_TAILSCALE_ACTIVE. */
        const val OLD_WRAPPER_FIXTURE = "tool-wrapper-before-network-mode.sh"
    }
}
