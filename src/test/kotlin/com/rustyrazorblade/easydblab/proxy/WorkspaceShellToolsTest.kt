package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What `up` and a VPC restore leave in a workspace's proxy env file, from the cluster's recorded state. */
class WorkspaceShellToolsTest {
    @TempDir
    lateinit var workspace: File

    private val tools = WorkspaceShellTools(ToolWrapperInstaller())

    @Test
    fun `a K3s in Docker workspace is recorded as direct`() {
        tools.prepare(workspace, ClusterState(name = "local", environment = Environment.K3sDocker("/d")))

        assertThat(ProxyEnvFile(workspace).read()).isEqualTo(ProxyEnv(tailscaleActive = false, tunnel = null, direct = true))
    }

    @Test
    fun `an EC2 plus K3s workspace loses a stale direct key and records its Tailscale flag`() {
        ProxyEnvFile(workspace).recordDirect(true)

        tools.prepare(workspace, ClusterState(name = "aws", tailscaleActive = true))

        assertThat(ProxyEnvFile(workspace).read()).isEqualTo(ProxyEnv(tailscaleActive = true, tunnel = null, direct = false))
    }
}
