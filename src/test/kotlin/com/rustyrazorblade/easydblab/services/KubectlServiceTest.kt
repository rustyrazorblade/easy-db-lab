package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.services.RecordingClusterTooling.Call
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The argument lists [DefaultKubectlService] builds, through a [RecordingClusterTooling]. They are
 * what both forms run, so a drifted argument fails here; the strings the SSH form renders from them
 * are pinned in [ClusterToolingSshCommandsTest].
 */
internal class KubectlServiceTest {
    private fun kubectl(
        vararg args: String,
        stdin: String? = null,
        echo: Boolean = true,
        shellQuoted: Set<Int> = emptySet(),
    ) = Call(ClusterTool.KUBECTL, args.toList(), stdin, echo, shellQuoted)

    @Test
    fun `applyUrl applies server-side from the url`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).applyUrl("https://example.com/manifest.yaml")

        assertThat(tooling.calls).containsExactly(kubectl("apply", "--server-side", "-f", "https://example.com/manifest.yaml"))
    }

    @Test
    fun `applyKustomize applies the kustomization at the url`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).applyKustomize("https://example.com/kustomize")

        assertThat(tooling.calls).containsExactly(kubectl("apply", "-k", "https://example.com/kustomize"))
    }

    @Test
    fun `applyContent passes the manifest as input to apply -f -`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).applyContent("apiVersion: v1\nkind: ConfigMap")

        assertThat(tooling.calls).containsExactly(kubectl("apply", "-f", "-", stdin = "apiVersion: v1\nkind: ConfigMap"))
    }

    @Test
    fun `createNamespace applies the dry run's output through input, with the dry run kept quiet`() {
        val rendered = "apiVersion: v1\nkind: Namespace\nmetadata:\n  name: cache\n"
        val tooling = RecordingClusterTooling { if (it.args.first() == "create") rendered else "" }

        DefaultKubectlService(tooling).createNamespace("cache")

        assertThat(tooling.calls).containsExactly(
            kubectl("create", "namespace", "cache", "--dry-run=client", "-o", "yaml", echo = false),
            kubectl("apply", "-f", "-", stdin = rendered),
        )
    }

    @Test
    fun `wait names the condition, object, namespace and timeout`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).wait("Deployment", "myapp", "Available", "mynamespace", "120s")

        assertThat(tooling.calls).containsExactly(
            kubectl("wait", "--for=condition=Available", "Deployment/myapp", "-n", "mynamespace", "--timeout", "120s"),
        )
    }

    @Test
    fun `delete ignores a missing object only when asked to`() {
        val tooling = RecordingClusterTooling()
        val service = DefaultKubectlService(tooling)

        service.delete("Pod", "mypod", "default", ignoreNotFound = true)
        service.delete("Pod", "mypod", "default", ignoreNotFound = false)

        assertThat(tooling.calls).containsExactly(
            kubectl("delete", "Pod/mypod", "-n", "default", "--ignore-not-found"),
            kubectl("delete", "Pod/mypod", "-n", "default"),
        )
    }

    @Test
    fun `listInAllNamespaces returns the trimmed non-blank lines, asking quietly`() {
        val tooling =
            RecordingClusterTooling { "cluster.postgresql.cnpg.io/postgres-duckdb\n\n  cluster.postgresql.cnpg.io/postgres-postgis \n" }

        val found = DefaultKubectlService(tooling).listInAllNamespaces("clusters.postgresql.cnpg.io")

        assertThat(found).containsExactly("cluster.postgresql.cnpg.io/postgres-duckdb", "cluster.postgresql.cnpg.io/postgres-postgis")
        assertThat(tooling.calls).containsExactly(
            kubectl("get", "clusters.postgresql.cnpg.io", "--all-namespaces", "-o", "name", echo = false),
        )
    }

    @Test
    fun `listInAllNamespaces fails when the lookup fails`() {
        val tooling = RecordingClusterTooling { throw IllegalStateException("api down") }

        assertThatThrownBy { DefaultKubectlService(tooling).listInAllNamespaces("pods") }.hasMessage("api down")
    }

    /**
     * The selector and every object name the cluster returned are raw values, marked for the SSH form
     * to quote. Quoting them here would hand literal quote characters to the local form's kubectl.
     */
    @Test
    fun `deleteBySelector looks the objects up quietly, then deletes exactly those by name`() {
        val tooling =
            RecordingClusterTooling { if (it.args.first() == "get") "deployment.apps/memcached\n\npod/memcached-abc\n" else "" }

        DefaultKubectlService(tooling).deleteBySelector(listOf("deployment", "pod"), "tier in (web, cache)", "default")

        assertThat(tooling.calls).containsExactly(
            kubectl(
                "get",
                "deployment,pod",
                "-l",
                "tier in (web, cache)",
                "-n",
                "default",
                "-o",
                "name",
                echo = false,
                shellQuoted = setOf(3),
            ),
            kubectl(
                "delete",
                "deployment.apps/memcached",
                "pod/memcached-abc",
                "-n",
                "default",
                "--ignore-not-found",
                shellQuoted = setOf(1, 2),
            ),
        )
    }

    /**
     * `kubectl delete -l` prints a bare "No resources found" when nothing matches, so nothing is
     * deleted, and nothing is echoed, when the lookup finds nothing.
     */
    @Test
    fun `deleteBySelector runs no delete when the selector matches nothing`() {
        val tooling = RecordingClusterTooling()

        DefaultKubectlService(tooling).deleteBySelector(listOf("pod"), "a=b", "default")

        assertThat(tooling.calls).hasSize(1)
        assertThat(tooling.calls.single().echo).isFalse()
    }

    @Test
    fun `deleteBySelector fails, deleting nothing, when the lookup fails`() {
        val tooling = RecordingClusterTooling { throw IllegalStateException("connection refused") }

        assertThatThrownBy { DefaultKubectlService(tooling).deleteBySelector(listOf("pod"), "a=b", "default") }
            .hasMessageContaining("connection refused")
        assertThat(tooling.calls).hasSize(1)
    }

    @Test
    fun `a failed tool's exception reaches the caller unchanged`() {
        val failure = ClusterToolFailedException(ClusterTool.KUBECTL, listOf("apply"), 1, "boom")
        val tooling = RecordingClusterTooling { throw failure }

        assertThatThrownBy { DefaultKubectlService(tooling).applyUrl("https://example.com/m.yaml") }.isSameAs(failure)
    }
}
