package com.rustyrazorblade.easydblab.services.tenancy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException

/** What [NamespaceEnsurer] does to the namespace a cluster installs into. */
sealed interface NamespacePlan {
    /** The namespace as it will be: named, and carrying the cluster's ownership labels. */
    val namespace: Namespace

    /** Absent: created with the labels. */
    data class Create(
        override val namespace: Namespace,
    ) : NamespacePlan

    /**
     * Present without the labels: adopted, its own labels kept and the cluster's added. [replaced]
     * lists each ownership label it already carried with another value, such as the cluster id of an
     * earlier `init` against the same Kubernetes cluster, which the adopt overwrites.
     */
    data class Adopt(
        override val namespace: Namespace,
        val replaced: List<ReplacedLabel> = emptyList(),
    ) : NamespacePlan

    /** Present with the labels already: nothing to do. */
    data class Unchanged(
        override val namespace: Namespace,
    ) : NamespacePlan
}

/** An ownership label [key] an adopted namespace carried as [previous], overwritten with [current]. */
data class ReplacedLabel(
    val key: String,
    val previous: String,
    val current: String,
)

/**
 * Makes sure the namespace an [InstallScope] names exists and is marked as the cluster's (design
 * D11). An absent namespace is created with the ownership labels. An existing one, `default`
 * included, is adopted: the user named it, so it is never refused, its own labels are kept and the
 * cluster's are added. The one place a namespace is created, shared by `up` and the capability probe.
 */
object NamespaceEnsurer {
    /** What to do, given the namespace as the API server has it ([existing], null when absent). */
    fun plan(
        existing: Namespace?,
        scope: InstallScope,
    ): NamespacePlan {
        if (existing == null) {
            return NamespacePlan.Create(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(scope.namespace.value)
                    .withLabels<String, String>(scope.labels)
                    .endMetadata()
                    .build(),
            )
        }
        val labels = existing.metadata.labels.orEmpty()
        if (labels.entries.containsAll(scope.labels.entries)) return NamespacePlan.Unchanged(existing)
        return NamespacePlan.Adopt(
            NamespaceBuilder(existing)
                .editMetadata()
                .withLabels<String, String>(labels + scope.labels)
                .endMetadata()
                .build(),
            replaced =
                scope.labels.mapNotNull { (key, value) ->
                    labels[key]?.takeIf { it != value }?.let { ReplacedLabel(key, it, value) }
                },
        )
    }

    /**
     * Reads the namespace, then creates or labels it as [plan] decides; returns what it did. A create
     * that loses a race with another creator (409) is planned again, once, from the namespace that
     * won; a second conflict is thrown. The returned plan's [NamespacePlan.namespace] is used only
     * for reporting: an adopted namespace is edited from the live object, not from the plan's copy.
     */
    fun ensure(
        client: KubernetesClient,
        scope: InstallScope,
    ): NamespacePlan =
        try {
            attempt(client, scope)
        } catch (e: KubernetesClientException) {
            if (e.code != Constants.HttpStatus.CONFLICT) throw e
            attempt(client, scope)
        }

    private fun attempt(
        client: KubernetesClient,
        scope: InstallScope,
    ): NamespacePlan {
        val name = scope.namespace.value
        return when (val plan = plan(client.namespaces().withName(name).get(), scope)) {
            is NamespacePlan.Create -> {
                client.namespaces().resource(plan.namespace).create()
                plan
            }
            is NamespacePlan.Adopt -> {
                client.namespaces().withName(name).edit { current ->
                    NamespaceBuilder(current)
                        .editMetadata()
                        .addToLabels(scope.labels)
                        .endMetadata()
                        .build()
                }
                plan
            }
            is NamespacePlan.Unchanged -> plan
        }
    }
}
