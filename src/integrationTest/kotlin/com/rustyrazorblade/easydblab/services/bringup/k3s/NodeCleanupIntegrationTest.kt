package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.Resource
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
import java.util.concurrent.TimeUnit
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
            val tail = SharedK3s.logTail(LOG_TAIL_CHARS)
            val relevant = tail.lines().filter { RELEVANT_LOG_LINE.containsMatchIn(it) }.joinToString("\n")
            System.err.println(
                "K3s log lines about the nodes, wrangler or the node controller after ${context.displayName} failed:\n$relevant\n" +
                    "K3s log tail:\n$tail",
            )
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
                release(client.nodes().withName(it))
                release(client.secrets().inNamespace(NodeCleanup.NAMESPACE).withName(NodeCleanup.passwordSecret(it)))
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
        SharedK3s.client().use { hold(it.nodes().withName(alias)) }
        var replacementUid: String? = null
        val replaced =
            NodeCleanup(
                WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()),
                waitSeconds = REPLACE_WAIT_SECONDS,
                afterDelete = {
                    SharedK3s.client().use { client ->
                        release(client.nodes().withName(alias))
                        client
                            .nodes()
                            .withName(alias)
                            .waitUntilCondition({ it == null }, OLD_NODE_GONE_SECONDS, TimeUnit.SECONDS)
                        replacementUid =
                            client
                                .nodes()
                                .resource(node(alias))
                                .create()
                                .metadata.uid
                    }
                },
            )

        val took = measureTime { replaced.clean(alias) }

        assertThat(took).isLessThan(REPLACE_WAIT_SECONDS.seconds)
        val now = SharedK3s.client().use { it.nodes().withName(alias).get() }
        assertThat(now.metadata.uid).isEqualTo(replacementUid)
    }

    @Test
    fun `a node held by a finalizer gives an error naming its deletionTimestamp and finalizer`() {
        val alias = "db91"
        create(alias, node = true, secret = false)
        SharedK3s.client().use { hold(it.nodes().withName(alias)) }
        val impatient = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), waitSeconds = HELD_WAIT_SECONDS)

        assertThatThrownBy { impatient.clean(alias) }
            .isInstanceOf(NodeCleanupTimeoutException::class.java)
            .hasMessageContaining("Node $alias (uid ")
            .hasMessageMatching("(?s).*deletionTimestamp=\\d{4}-.*")
            .hasMessageContaining(HOLD_FINALIZER)
    }

    @Test
    fun `a secret held by a finalizer gives an error that names the secret`() {
        val alias = "db92"
        create(alias, node = false, secret = true)
        SharedK3s.client().use {
            hold(it.secrets().inNamespace(NodeCleanup.NAMESPACE).withName(NodeCleanup.passwordSecret(alias)))
        }
        val impatient = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), waitSeconds = HELD_WAIT_SECONDS)

        assertThatThrownBy { impatient.clean(alias) }
            .isInstanceOf(NodeCleanupTimeoutException::class.java)
            .hasMessageContaining("Secret ${NodeCleanup.passwordSecret(alias)} (uid ")
            .hasMessageContaining(HOLD_FINALIZER)
    }

    private fun node(alias: String) =
        NodeBuilder()
            .withNewMetadata()
            .withName(alias)
            .endMetadata()
            .build()

    private fun <T : HasMetadata> finalizersOf(resource: Resource<T>): List<String> {
        val meta = resource.get()?.metadata
        return meta?.finalizers.orEmpty()
    }

    /** Adds [HOLD_FINALIZER] with a JSON patch `add`, so a finalizer K3s put there stays. */
    private fun <T : HasMetadata> hold(resource: Resource<T>) {
        val first = finalizersOf(resource).isEmpty()
        val path = if (first) "/metadata/finalizers" else "/metadata/finalizers/-"
        val value = if (first) "[\"$HOLD_FINALIZER\"]" else "\"$HOLD_FINALIZER\""
        resource.patch(PatchContext.of(PatchType.JSON), """[{"op":"add","path":"$path","value":$value}]""")
    }

    /**
     * Removes [HOLD_FINALIZER] and nothing else, with a JSON patch that tests the entry it removes,
     * so a finalizer K3s removed or added meanwhile is not lost; a failed test reads the list again.
     */
    private fun <T : HasMetadata> release(resource: Resource<T>) {
        repeat(RELEASE_ATTEMPTS) {
            val index = finalizersOf(resource).indexOf(HOLD_FINALIZER)
            if (index < 0) return
            val path = "/metadata/finalizers/$index"
            val ops =
                """[{"op":"test","path":"$path","value":"$HOLD_FINALIZER"},{"op":"remove","path":"$path"}]"""
            try {
                resource.patch(PatchContext.of(PatchType.JSON), ops)
                return
            } catch (e: KubernetesClientException) {
                // 404: the object finished deleting. Any other failure is a lost race on the list.
                if (e.code == Constants.HttpStatus.NOT_FOUND) return
            }
        }
        error("could not remove $HOLD_FINALIZER from ${resource.get()?.metadata?.name}")
    }

    private companion object {
        const val HOLD_FINALIZER = "easydblab.test/hold"
        const val REPLACE_WAIT_SECONDS = 30L
        const val HELD_WAIT_SECONDS = 3L
        const val RELEASE_ATTEMPTS = 5
        const val OLD_NODE_GONE_SECONDS = 20L
        const val LOG_TAIL_CHARS = 64_000
        val RELEVANT_LOG_LINE = Regex("wrangler|node-controller|\\bdb\\d+")
    }
}
