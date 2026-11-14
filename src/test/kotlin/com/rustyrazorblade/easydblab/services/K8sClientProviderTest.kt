package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

class K8sClientProviderTest {
    @TempDir
    lateinit var workspace: File

    @TempDir
    lateinit var userDirectory: File

    private class RecordingFactory(
        private val client: KubernetesClient,
    ) : KubernetesClientFactory {
        val paths = mutableListOf<Path>()

        override fun createClient(kubeconfigPath: Path): KubernetesClient {
            paths.add(kubeconfigPath)
            return client
        }
    }

    private fun provider(
        factory: KubernetesClientFactory,
        workingDirectory: File = workspace,
    ) = K8sClientProvider(Context(easyDbLabUserDirectory = userDirectory, workingDirectory = workingDirectory), factory)

    @Test
    fun `builds the client from the kubeconfig of the working directory the context names`() {
        val workspaceKubeconfig = File(workspace, "kubeconfig").apply { writeText("apiVersion: v1\n") }
        val factory = RecordingFactory(mock())

        provider(factory).createClient()

        assertThat(factory.paths).containsExactly(workspaceKubeconfig.absoluteFile.toPath())
    }

    @Test
    fun `fails with the absolute path when the workspace has no kubeconfig, and builds no client`() {
        val factory = RecordingFactory(mock())

        assertThatThrownBy { provider(factory).createClient() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(File(workspace, "kubeconfig").absolutePath)
        assertThat(factory.paths).isEmpty()
    }
}
