package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * [K8sClientProvider] builds each client from the workspace kubeconfig the [WorkspaceKubeApiLocator]
 * names, whatever directory the process started in, and builds none when the workspace has no
 * kubeconfig.
 */
class K8sClientProviderTest {
    @TempDir
    lateinit var workspace: File

    private class RecordingFactory(
        private val client: KubernetesClient,
    ) : KubernetesClientFactory {
        val requested = mutableListOf<KubeApi>()

        override fun createClient(api: KubeApi): KubernetesClient {
            requested.add(api)
            return client
        }
    }

    private fun provider(factory: KubernetesClientFactory): K8sClientProvider {
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(ClusterState(name = "test", kubeContext = "pinned"))
        return K8sClientProvider(WorkspaceKubeApiLocator(stateManager, workspace), factory)
    }

    @Test
    fun `builds the client from the kubeconfig of the workspace, with the pinned context`() {
        val workspaceKubeconfig = File(workspace, "kubeconfig").apply { writeText("apiVersion: v1\n") }
        val factory = RecordingFactory(mock())

        provider(factory).createClient()

        assertThat(factory.requested).containsExactly(KubeApi(workspaceKubeconfig.absoluteFile.toPath(), "pinned"))
    }

    @Test
    fun `fails with the absolute path when the workspace has no kubeconfig, and builds no client`() {
        val factory = RecordingFactory(mock())

        assertThatThrownBy { provider(factory).createClient() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(File(workspace, "kubeconfig").absolutePath)
        assertThat(factory.requested).isEmpty()
    }
}
