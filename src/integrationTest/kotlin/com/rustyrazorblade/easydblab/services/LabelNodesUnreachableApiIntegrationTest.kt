package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A `label` step on an API server that cannot be reached stops at the first node, instead of
 * waiting out one request timeout per node in turn. The client points at a local port nothing
 * listens on, so the first patch fails with a real transport error and no HTTP answer.
 */
class LabelNodesUnreachableApiIntegrationTest {
    /** Builds a client for [server] with no request retries, so the refused connection fails at once. */
    private class NoRetryFactory(
        private val server: String,
    ) : KubernetesClientFactory {
        override fun createClient(kubeconfigPath: Path): KubernetesClient =
            KubernetesClientBuilder()
                .withConfig(
                    Config.empty().apply {
                        masterUrl = server
                        requestRetryBackoffLimit = 0
                    },
                ).build()
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `a transport failure on the first node leaves the later nodes not tried`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val operations =
            DefaultK8sManifestOperations(
                WorkspaceK8s.provider("apiVersion: v1\n", NoRetryFactory("http://127.0.0.1:$closedPort")),
                EventBus(),
            )

        val outcomes = operations.labelNodes(listOf("db0", "db1", "db2"), mapOf("easydblab/qa" to "yes"))

        assertThat(outcomes.keys).containsExactly("db0", "db1", "db2")
        val first = outcomes.getValue("db0").exceptionOrNull()
        assertThat(first).isInstanceOf(KubernetesClientException::class.java)
        assertThat((first as KubernetesClientException).code).isLessThanOrEqualTo(0)
        listOf("db1", "db2").forEach { later ->
            val failure = outcomes.getValue(later).exceptionOrNull()
            assertThat(failure)
                .hasMessageStartingWith("Node $later was not tried, because labelling db0 failed:")
                .hasCause(first)
        }
    }
}
