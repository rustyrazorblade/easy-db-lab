package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * The client is built from the workspace's kubeconfig, whatever the process's current directory is.
 */
class K8sClientProviderTest {
    @TempDir
    lateinit var workspace: File

    private val host = ClusterHost("1.2.3.4", "10.0.0.1", "control0", "us-west-2a", "i-1")

    private fun provider(): K8sClientProvider {
        val manager: ClusterStateManager = mock()
        whenever(manager.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
        return K8sClientProvider(manager, workspace)
    }

    @Test
    fun `client is configured from the workspace kubeconfig`() {
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText(
            """
            apiVersion: v1
            kind: Config
            clusters:
            - name: c
              cluster:
                server: https://distinctive.example:6443
            contexts:
            - name: x
              context:
                cluster: c
                user: u
            current-context: x
            users:
            - name: u
              user:
                token: t
            """.trimIndent(),
        )

        provider().createClient(host).use { client ->
            assertThat(client.configuration.masterUrl).startsWith("https://distinctive.example:6443")
        }
    }

    @Test
    fun `a missing kubeconfig names the workspace path`() {
        assertThatThrownBy { provider().createClient(host) }
            .hasMessageContaining(File(workspace, Constants.K3s.LOCAL_KUBECONFIG).absolutePath)
    }
}
