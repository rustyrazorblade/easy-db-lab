package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.concurrent.thread

/**
 * Proves [NodeCleanup] against a real K3s API server: whichever of a replaced node's Node object
 * and `<alias>.node-password.k3s` secret exist are deleted, one already gone is no failure,
 * another node's objects are left alone, a Node recreated under the alias does not time the wait
 * out, and a Node a finalizer holds gives an error that says what holds it.
 *
 * A failing test prints the K3s container's log tail to stderr, which the test report keeps and CI
 * uploads with it.
 */
class NodeCleanupIntegrationTest {
    /** Prints the K3s log tail when a test fails, so a stalled controller shows in the report. */
    class K3sLogOnFailure : TestWatcher {
        override fun testFailed(
            context: ExtensionContext,
            cause: Throwable?,
        ) {
            System.err.println("K3s log tail after ${context.displayName} failed:\n${SharedK3s.logTail()}")
        }
    }

    @JvmField
    @RegisterExtension
    val k3sLog = K3sLogOnFailure()

    private val cleanup = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()))
    private val aliases = mutableListOf<String>()

    @AfterEach
    fun removeLeftovers() {
        SharedK3s.client().use { client ->
            aliases.forEach {
                if (exists(it).first) release(client, it)
                client.nodes().withName(it).delete()
                client
                    .secrets()
                    .inNamespace(NodeCleanup.NAMESPACE)
                    .withName(NodeCleanup.passwordSecret(it))
                    .delete()
            }
        }
    }

    private fun create(
        alias: String,
        node: Boolean,
        secret: Boolean,
    ) {
        aliases += alias
        SharedK3s.client().use { client ->
            if (node) {
                client
                    .nodes()
                    .resource(
                        NodeBuilder()
                            .withNewMetadata()
                            .withName(alias)
                            .endMetadata()
                            .build(),
                    ).create()
            }
            if (secret) {
                val password =
                    SecretBuilder()
                        .withNewMetadata()
                        .withName(NodeCleanup.passwordSecret(alias))
                        .withNamespace(NodeCleanup.NAMESPACE)
                        .endMetadata()
                        .addToStringData("hash", "x")
                        .build()
                client.secrets().resource(password).create()
            }
        }
    }

    private fun exists(alias: String): Pair<Boolean, Boolean> =
        SharedK3s.client().use { client ->
            val node = client.nodes().withName(alias).get() != null
            val secret =
                client
                    .secrets()
                    .inNamespace(NodeCleanup.NAMESPACE)
                    .withName(NodeCleanup.passwordSecret(alias))
                    .get() != null
            node to secret
        }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `whichever of the node and its password secret exist are deleted, and a missing one is no failure`(
        node: Boolean,
        secret: Boolean,
    ) {
        val alias = "db${aliases.size + 70}-${node.toString()[0]}${secret.toString()[0]}"
        create(alias, node, secret)

        cleanup.clean(alias)

        assertThat(exists(alias)).isEqualTo(false to false)
    }

    @Test
    fun `another node's objects are left alone`() {
        create("db80", node = true, secret = true)
        create("db81", node = true, secret = true)

        cleanup.clean("db80")

        assertThat(exists("db80")).isEqualTo(false to false)
        assertThat(exists("db81")).isEqualTo(true to true)
    }

    @Test
    fun `a node recreated under the alias does not time the wait out`() {
        val alias = "db90"
        create(alias, node = true, secret = false)
        hold(alias)
        val patient = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), waitSeconds = REPLACE_WAIT_SECONDS)
        val replacer =
            thread {
                Thread.sleep(REPLACE_DELAY_MILLIS)
                SharedK3s.client().use { client ->
                    release(client, alias)
                    client.nodes().resource(node(alias)).create()
                }
            }

        patient.clean(alias)
        replacer.join()

        assertThat(exists(alias).first).isTrue()
    }

    @Test
    fun `a node held by a finalizer gives an error naming its deletionTimestamp and finalizer`() {
        val alias = "db91"
        create(alias, node = true, secret = false)
        hold(alias)
        val impatient = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), waitSeconds = HELD_WAIT_SECONDS)

        assertThatThrownBy { impatient.clean(alias) }
            .isInstanceOf(NodeCleanupTimeoutException::class.java)
            .hasMessageContaining("Node $alias (uid ")
            .hasMessageMatching("(?s).*deletionTimestamp=\\d{4}-.*")
            .hasMessageContaining("finalizers=[$HOLD_FINALIZER]")
    }

    private fun node(alias: String) =
        NodeBuilder()
            .withNewMetadata()
            .withName(alias)
            .endMetadata()
            .build()

    private fun hold(alias: String) {
        SharedK3s.client().use { client ->
            client.nodes().withName(alias).edit { node ->
                node.also { it.metadata.finalizers = listOf(HOLD_FINALIZER) }
            }
        }
    }

    private fun release(
        client: KubernetesClient,
        alias: String,
    ) {
        client.nodes().withName(alias).edit { node ->
            node.also { it.metadata.finalizers = emptyList() }
        }
    }

    private companion object {
        const val HOLD_FINALIZER = "easydblab.test/hold"
        const val REPLACE_DELAY_MILLIS = 1500L
        const val REPLACE_WAIT_SECONDS = 20L
        const val HELD_WAIT_SECONDS = 3L
    }
}
