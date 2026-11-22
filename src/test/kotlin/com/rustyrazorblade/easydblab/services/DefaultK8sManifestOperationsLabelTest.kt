package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

/**
 * The batch rule of [DefaultK8sManifestOperations.labelNodes] for an interrupt. The stub client
 * sets the interrupt flag and fails with an exception that carries no HTTP code, so only the
 * interrupt check can stop the batch. The transport-failure branch runs against a real closed port in
 * `LabelNodesUnreachableApiIntegrationTest`.
 */
class DefaultK8sManifestOperationsLabelTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `an interrupt while labelling leaves the later nodes not tried`() {
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\n")
        var calls = 0
        val client =
            mock<KubernetesClient> {
                on { nodes() } doAnswer {
                    calls++
                    Thread.currentThread().interrupt()
                    error("interrupted while waiting for the API")
                }
            }
        val factory =
            object : KubernetesClientFactory {
                override fun createClient(kubeconfigPath: Path): KubernetesClient = client
            }
        val operations =
            DefaultK8sManifestOperations(
                K8sClientProvider(Context(easyDbLabUserDirectory = workspace, workingDirectory = workspace), factory),
                EventBus(),
            )

        val outcomes =
            try {
                operations.labelNodes(listOf("db0", "db1", "db2"), mapOf("easydblab/qa" to "yes"))
            } finally {
                Thread.interrupted()
            }

        assertThat(calls).isEqualTo(1)
        val first = outcomes.getValue("db0").exceptionOrNull()
        assertThat(first).hasMessage("interrupted while waiting for the API")
        listOf("db1", "db2").forEach { later ->
            assertThat(outcomes.getValue(later).exceptionOrNull())
                .hasMessageStartingWith("Node $later was not tried, because labelling db0 failed:")
                .hasCause(first)
        }
    }
}
