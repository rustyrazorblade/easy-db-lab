package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeAddressBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Proves [NodeWatcher] against a real K3s API server: the K3s container's own node completes
 * registered and Ready; a Node created later completes registered when it appears and Ready only
 * once its condition turns `True`; and a Node under the alias with another IP (the instance a
 * replacement took over from) completes nothing.
 */
class NodeWatcherIntegrationTest {
    private val watcher = NodeWatcher.open(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()))
    private val created = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        watcher.close()
        SharedK3s.client().use { client -> created.forEach { client.nodes().withName(it).delete() } }
    }

    private fun containerNode(): Node =
        SharedK3s.client().use { client ->
            client
                .nodes()
                .list()
                .items
                .first { it.metadata.name !in created }
        }

    private fun createNode(
        name: String,
        ip: String,
    ) {
        val node =
            NodeBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewStatus()
                .withAddresses(NodeAddressBuilder().withType("InternalIP").withAddress(ip).build())
                .endStatus()
                .build()
        SharedK3s.client().use { it.nodes().resource(node).create() }
        created += name
    }

    private fun markReady(name: String) {
        SharedK3s.client().use { client ->
            client.nodes().withName(name).editStatus { node ->
                NodeBuilder(node)
                    .editStatus()
                    .withConditions(NodeConditionBuilder().withType("Ready").withStatus("True").build())
                    .endStatus()
                    .build()
            }
        }
    }

    @Test
    fun `a node that is already registered and Ready completes both`() {
        val node = containerNode()

        val watch = watcher.watch(node.metadata.name, NodeWatcher.internalIp(node).orEmpty())

        watch.ready.get(30, TimeUnit.SECONDS)
        assertThat(watch.registered).isDone()
    }

    @Test
    fun `a node that registers later completes registered, and Ready only on its condition`() {
        val watch = watcher.watch("db900", "10.9.0.1")
        assertThat(watch.registered).isNotDone()

        createNode("db900", "10.9.0.1")
        watch.registered.get(30, TimeUnit.SECONDS)
        assertThat(watch.ready).isNotDone()

        markReady("db900")
        watch.ready.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `the Node of the instance a replacement took over from completes nothing`() {
        createNode("db901", "10.9.0.2")
        markReady("db901")

        val watch = watcher.watch("db901", "10.9.0.99")

        assertThat(runCatching { watch.registered.get(3, TimeUnit.SECONDS) }.isFailure).isTrue()
        assertThat(watch.ready).isNotDone()
    }
}
