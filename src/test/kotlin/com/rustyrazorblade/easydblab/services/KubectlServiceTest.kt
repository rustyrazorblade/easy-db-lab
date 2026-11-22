package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The decisions [DefaultKubectlService] makes, through a [RecordingClusterTooling]: how it reads
 * kubectl's output and what it runs next. The commands each method runs on the control node are in
 * [ClusterToolingSshCommandsTest].
 */
internal class KubectlServiceTest {
    @Test
    fun `createNamespace applies a Namespace manifest of that name through input`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).createNamespace("cache")

        val call = tooling.calls.single()
        assertThat(call.args).containsExactly("apply", "-f", "-")
        assertThat(Serialization.unmarshal(call.input, Namespace::class.java).metadata.name).isEqualTo("cache")
    }

    @Test
    fun `listInAllNamespaces returns the trimmed non-blank lines, asking quietly`() {
        val tooling =
            RecordingClusterTooling { "cluster.postgresql.cnpg.io/postgres-duckdb\n\n  cluster.postgresql.cnpg.io/postgres-postgis \n" }

        val found = DefaultKubectlService(tooling).listInAllNamespaces("clusters.postgresql.cnpg.io")

        assertThat(found).containsExactly("cluster.postgresql.cnpg.io/postgres-duckdb", "cluster.postgresql.cnpg.io/postgres-postgis")
        assertThat(tooling.calls.single().echo).isFalse()
    }

    @Test
    fun `deleteBySelector looks the objects up quietly, then deletes exactly those by name`() {
        val tooling =
            RecordingClusterTooling { if (it.args.first() == "get") "deployment.apps/memcached\n\npod/memcached-abc\n" else "" }

        DefaultKubectlService(tooling).deleteBySelector(listOf("deployment", "pod"), "tier in (web, cache)", "default")

        assertThat(tooling.calls.map { it.echo }).containsExactly(false, true)
        assertThat(tooling.calls[1].args)
            .containsExactly("delete", "deployment.apps/memcached", "pod/memcached-abc", "-n", "default", "--ignore-not-found")
    }

    /** `kubectl delete -l` prints a bare "No resources found" when nothing matches, so nothing is deleted when the lookup finds nothing. */
    @Test
    fun `deleteBySelector runs no delete when the selector matches nothing`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).deleteBySelector(listOf("pod"), "a=b", "default")

        assertThat(tooling.calls.map { it.args.first() }).containsExactly("get")
    }

    @Test
    fun `deleteBySelector fails, deleting nothing, when the lookup fails`() {
        val tooling = RecordingClusterTooling { throw IllegalStateException("connection refused") }

        assertThatThrownBy { DefaultKubectlService(tooling).deleteBySelector(listOf("pod"), "a=b", "default") }
            .hasMessageContaining("connection refused")
        assertThat(tooling.calls).hasSize(1)
    }
}
