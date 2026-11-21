package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests [DefaultK8sManifestOperations.nodesMatching] against the fabric8 mock server: a node matches
 * only when it carries every label, which is what the deploy's placement check relies on.
 */
@EnableKubernetesMockClient(crud = true)
class DefaultK8sManifestOperationsTest {
    private lateinit var operations: DefaultK8sManifestOperations

    lateinit var server: KubernetesMockServer

    @BeforeEach
    fun setup() {
        val clientProvider = mock<K8sClientProvider>()
        whenever(clientProvider.createClient()).thenAnswer { server.createClient() }
        operations = DefaultK8sManifestOperations(clientProvider, EventBus())
    }

    private fun node(
        name: String,
        labels: Map<String, String>,
    ) {
        server.createClient().use { client ->
            client
                .nodes()
                .resource(
                    NodeBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .addToLabels(labels)
                        .endMetadata()
                        .build(),
                ).create()
        }
    }

    @Test
    fun `a node matches only when it carries every label`() {
        val controlPlane = "node-role.kubernetes.io/control-plane"
        node("control0", mapOf(controlPlane to "true"))
        node("control1", mapOf(controlPlane to "true", "pool" to "observability"))
        node("db0", mapOf("pool" to "observability"))

        assertThat(operations.nodesMatching(mapOf(controlPlane to "true", "pool" to "observability")).getOrThrow())
            .containsExactly("control1")
        assertThat(operations.nodesMatching(mapOf("pool" to "batch")).getOrThrow()).isEmpty()
        assertThat(operations.nodesMatching(emptyMap()).getOrThrow()).containsExactlyInAnyOrder("control0", "control1", "db0")
    }

    @Test
    fun `the allocatable resources are read only from the nodes carrying every label`() {
        server.createClient().use { client ->
            listOf("db0" to "30000Mi", "db1" to "31000Mi", "app0" to "1Gi").forEach { (name, memory) ->
                client
                    .nodes()
                    .resource(
                        NodeBuilder()
                            .withNewMetadata()
                            .withName(name)
                            .addToLabels("type", name.dropLast(1))
                            .endMetadata()
                            .withNewStatus()
                            .addToAllocatable("memory", Quantity(memory))
                            .addToAllocatable("cpu", Quantity("8"))
                            .endStatus()
                            .build(),
                    ).create()
            }
        }

        assertThat(operations.nodeAllocatable(mapOf("type" to "db")).getOrThrow())
            .containsExactlyInAnyOrder(NodeAllocatable(30000, 8), NodeAllocatable(31000, 8))
    }
}
