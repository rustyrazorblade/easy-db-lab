package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Proves [NodeCleanup] against a real K3s API server: whichever of a replaced node's Node object
 * and `<alias>.node-password.k3s` secret exist are deleted, one already gone is no failure, and
 * another node's objects are left alone.
 */
class NodeCleanupIntegrationTest {
    private val cleanup = NodeCleanup(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()))
    private val aliases = mutableListOf<String>()

    @AfterEach
    fun removeLeftovers() {
        SharedK3s.client().use { client ->
            aliases.forEach {
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
}
