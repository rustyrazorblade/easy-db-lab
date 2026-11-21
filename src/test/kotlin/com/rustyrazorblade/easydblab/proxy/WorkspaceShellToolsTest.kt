package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What `up` and a VPC restore leave in a workspace's proxy env file, from the cluster's recorded state. */
class WorkspaceShellToolsTest {
    @TempDir
    lateinit var workspace: File

    private val tools = WorkspaceShellTools(ToolWrapperInstaller(), EnvScriptWriter(DirectSshRoute(22)))

    @Test
    fun `a K3s in Docker workspace is recorded as direct whatever the state's mode`() {
        tools.prepare(workspace, ClusterState(name = "local", networkMode = NetworkMode.Socks, environment = Environment.K3sDocker("/d")))

        assertThat(ProxyEnvFile(workspace).read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Direct, tunnel = null))
    }

    @Test
    fun `an EC2 plus K3s workspace loses a stale direct mode and records its own`() {
        ProxyEnvFile(workspace).recordNetworkMode(NetworkMode.Direct)

        tools.prepare(workspace, ClusterState(name = "aws", networkMode = NetworkMode.Tailscale))

        assertThat(ProxyEnvFile(workspace).read()).isEqualTo(ProxyEnv(networkMode = NetworkMode.Tailscale, tunnel = null))
    }
}
