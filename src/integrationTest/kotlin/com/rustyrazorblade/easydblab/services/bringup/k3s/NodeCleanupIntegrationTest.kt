package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
        val replaced =
            NodeCleanup(
                WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()),
                waitSeconds = REPLACE_WAIT_SECONDS,
                afterDelete = {
                    SharedK3s.client().use { client ->
                        release(client, alias)
                        client.nodes().resource(node(alias)).create()
                    }
                },
            )

        val took = measureTime { replaced.clean(alias) }

        assertThat(took).isLessThan(REPLACE_WAIT_SECONDS.seconds)

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
            setFinalizers(client, alias, """["$HOLD_FINALIZER"]""")
        }
    }

    private fun release(
        client: KubernetesClient,
        alias: String,
    ) {
        try {
            setFinalizers(client, alias, "null")
        } catch (e: KubernetesClientException) {
            // The Node finished deleting before the edit; nothing is left to release.
            if (e.code != HTTP_NOT_FOUND) throw e
        }
    }

    /** A merge patch carries no resourceVersion, so K3s's own writes to the Node cannot conflict with it. */
    private fun setFinalizers(
        client: KubernetesClient,
        alias: String,
        json: String,
    ) {
        client
            .nodes()
            .withName(alias)
            .patch(PatchContext.of(PatchType.JSON_MERGE), """{"metadata":{"finalizers":$json}}""")
    }

    private companion object {
        const val HOLD_FINALIZER = "easydblab.test/hold"
        const val HTTP_NOT_FOUND = 404
        const val REPLACE_WAIT_SECONDS = 5L
        const val HELD_WAIT_SECONDS = 3L
    }
}
