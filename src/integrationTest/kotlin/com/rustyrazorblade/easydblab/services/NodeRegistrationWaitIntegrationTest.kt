package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Proves [DefaultK8sNamespaceOperations.waitForNodesRegistered] against a real K3s cluster: it
 * returns once every expected node exists, and fails naming the nodes that never registered
 * once its deadline passes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeRegistrationWaitIntegrationTest {
    private val controlHost =
        ClusterHost(publicIp = "1.2.3.4", privateIp = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    private lateinit var ops: DefaultK8sNamespaceOperations
    private lateinit var nodeName: String

    @BeforeAll
    fun setup() {
        // Each operation closes the client it is handed, so every call gets a fresh one.
        val clientProvider = WorkspaceK8s.provider(SharedK3s.kubeConfigYaml())
        ops = DefaultK8sNamespaceOperations(clientProvider, EventBus(), podPollInterval = Duration.ofMillis(200))
        nodeName =
            SharedK3s.client().use { client ->
                client
                    .nodes()
                    .list()
                    .items
                    .single()
                    .metadata.name
            }
    }

    @Test
    fun `returns once every expected node is registered`() {
        val result = ops.waitForNodesRegistered(listOf(nodeName), Duration.ofSeconds(20))

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `fails naming only the nodes that never registered`() {
        val result = ops.waitForNodesRegistered(listOf(nodeName, "db999"), Duration.ofSeconds(2))

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("db999")
            .hasMessageNotContaining(nodeName)
    }

    @Test
    fun `names the first 20 nodes that never registered and counts the rest`() {
        val missing = (900 until 925).map { "db$it" }

        val result = ops.waitForNodesRegistered(missing, Duration.ofSeconds(1))

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageEndingWith("db918, db919, and 5 more")
            .hasMessageNotContaining("db920")
    }
}
