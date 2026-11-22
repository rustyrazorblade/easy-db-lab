package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScopeSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.tenancy.NamespaceEnsurer
import com.rustyrazorblade.easydblab.services.tenancy.NamespacePlan
import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * The first of `up`'s post-steps: the namespace the cluster installs into exists and carries the
 * cluster's ownership labels before anything is applied into it (design D11).
 */
fun interface InstallNamespace {
    /** Creates or adopts the namespace; throws an [IllegalStateException] naming it when the API server refuses. */
    fun ensure()
}

/**
 * The [InstallNamespace] of the workspace's cluster: [NamespaceEnsurer] over the workspace's
 * Kubernetes client, at the scope the cluster state resolves to. Reports what it did, since an
 * adopted namespace is one the user named and now carries this cluster's labels.
 */
class K8sInstallNamespace(
    private val clientProvider: K8sClientProvider,
    private val scopeSource: InstallScopeSource,
    private val eventBus: EventBus,
) : InstallNamespace {
    override fun ensure() {
        val scope = scopeSource.current()
        val namespace = scope.namespace.value
        val plan =
            try {
                clientProvider.createClient().use { client -> NamespaceEnsurer.ensure(client, scope) }
            } catch (e: KubernetesClientException) {
                // Kept as the cause, so the Kubernetes error handler still describes a 403.
                val reason = e.status?.message ?: e.message.orEmpty()
                throw IllegalStateException("Ensuring the install namespace '$namespace' failed: $reason", e)
            }
        val outcome =
            when (plan) {
                is NamespacePlan.Create -> Event.K8s.NamespaceOutcome.CREATED
                is NamespacePlan.Adopt -> Event.K8s.NamespaceOutcome.ADOPTED
                is NamespacePlan.Unchanged -> Event.K8s.NamespaceOutcome.UNCHANGED
            }
        eventBus.emit(Event.K8s.InstallNamespaceReady(namespace, outcome))
    }
}
