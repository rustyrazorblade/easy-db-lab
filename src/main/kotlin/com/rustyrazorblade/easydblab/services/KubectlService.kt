package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants

/** What the kit steps and commands ask of kubectl, wherever it runs; [DefaultKubectlService] runs it through [ClusterTooling]. */
interface KubectlService {
    /**
     * Creates namespace [name] if it is missing and leaves it alone otherwise: a client-side dry run
     * renders the Namespace, and applying that output is what makes the call idempotent.
     */
    fun createNamespace(name: String)

    fun applyUrl(url: String)

    fun applyKustomize(url: String)

    /**
     * Apply YAML content with kubectl, passing it as input to the tool (see [ClusterTooling.run]).
     * Handles any resource kind including custom CRDs not known to the Fabric8 client.
     * Uses client-side apply intentionally — manifests applied here are small and do not
     * hit the 262 KB annotation limit. See [applyUrl] for the --server-side workaround.
     */
    fun applyContent(yamlContent: String)

    fun wait(
        kind: String,
        name: String,
        condition: String,
        namespace: String,
        timeout: String,
    )

    fun delete(
        kind: String,
        name: String,
        namespace: String,
        ignoreNotFound: Boolean,
    )

    /**
     * Deletes every object of [kinds] in [namespace] that the label [selector] matches. Nothing
     * matching is not an error and prints nothing; a failed lookup or delete throws.
     */
    fun deleteBySelector(
        kinds: List<String>,
        selector: String,
        namespace: String,
    )

    /**
     * The `kind/name` of every object of [resource] (a resource type such as
     * `clusters.postgresql.cnpg.io`) in any namespace. A failed lookup throws.
     */
    fun listInAllNamespaces(resource: String): List<String>
}

/**
 * Runs kubectl through [ClusterTooling], so the same calls reach a cluster over SSH on AWS and as a
 * local process on a local cluster. It builds argument lists and marks the ones the SSH form must
 * quote; it never quotes a value itself, because the local form would hand the quotes to kubectl.
 */
class DefaultKubectlService(
    private val tooling: ClusterTooling,
) : KubectlService {
    override fun applyContent(yamlContent: String) {
        tooling.run(ClusterTool.KUBECTL, APPLY_FROM_INPUT, stdin = yamlContent)
    }

    override fun createNamespace(name: String) {
        // The dry run's output is the manifest to apply, so it must not be echoed.
        val manifest = tooling.run(ClusterTool.KUBECTL, listOf("create", "namespace", name, "--dry-run=client", "-o", "yaml"), echo = false)
        tooling.run(ClusterTool.KUBECTL, APPLY_FROM_INPUT, stdin = manifest)
    }

    override fun applyUrl(url: String) {
        // --server-side required: some operator CRD bundles (e.g. TiDB) exceed the 262 KB
        // client-side apply annotation limit and fail with "metadata too large" without it.
        tooling.run(ClusterTool.KUBECTL, listOf("apply", "--server-side", "-f", url))
    }

    override fun applyKustomize(url: String) {
        tooling.run(ClusterTool.KUBECTL, listOf("apply", "-k", url))
    }

    override fun wait(
        kind: String,
        name: String,
        condition: String,
        namespace: String,
        timeout: String,
    ) {
        val args =
            buildList {
                addAll(listOf("wait", "--for=condition=$condition", "$kind/$name"))
                addAll(listOf("-n", namespace))
                addAll(listOf("--timeout", timeout))
            }
        tooling.run(ClusterTool.KUBECTL, args)
    }

    override fun delete(
        kind: String,
        name: String,
        namespace: String,
        ignoreNotFound: Boolean,
    ) {
        val args =
            buildList {
                addAll(listOf("delete", "$kind/$name", "-n", namespace))
                if (ignoreNotFound) add("--ignore-not-found")
            }
        tooling.run(ClusterTool.KUBECTL, args)
    }

    override fun deleteBySelector(
        kinds: List<String>,
        selector: String,
        namespace: String,
    ) {
        // `kubectl delete -l` prints a bare "No resources found" when nothing matches, so look the
        // objects up without echoing the result and delete only what the selector found.
        val lookup = listOf("get", kinds.joinToString(","), "-l", selector, "-n", namespace, "-o", "name")
        val found = nonBlankLines(tooling.run(ClusterTool.KUBECTL, lookup, echo = false, shellQuoted = setOf(SELECTOR_INDEX)))
        if (found.isEmpty()) return
        // The names come from the cluster, so each one is quoted for the SSH form: argument 0 is "delete".
        tooling.run(
            ClusterTool.KUBECTL,
            listOf("delete") + found + listOf("-n", namespace, "--ignore-not-found"),
            shellQuoted = (1..found.size).toSet(),
        )
    }

    override fun listInAllNamespaces(resource: String): List<String> =
        nonBlankLines(tooling.run(ClusterTool.KUBECTL, listOf("get", resource, "--all-namespaces", "-o", "name"), echo = false))

    private fun nonBlankLines(output: String): List<String> =
        output
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private companion object {
        /** `apply -f -`: the manifest comes in as input to the tool. */
        val APPLY_FROM_INPUT = listOf("apply", "-f", Constants.ClusterTooling.STDIN_ARGUMENT)

        /** Where the label selector sits in the lookup's arguments: `get <kinds> -l <selector> ...`. */
        const val SELECTOR_INDEX = 3
    }
}
