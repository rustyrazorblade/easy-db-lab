package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment

/**
 * Tells the operator which observability workloads an apply is about to roll.
 *
 * Nothing force-restarts a workload: each carries a hash of its rendered pod template and of the
 * ConfigMaps it reads (see [com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator]).
 * Kubernetes rolls a workload on any pod-template change, and that hash changes exactly when the
 * template or its configuration does, so an equal hash means the apply leaves the workload running.
 * Without a report, `up` and `grafana update-config` would give no sign of which workloads picked up
 * a change and which kept running. Call it with the hashed resources before applying them: it
 * compares each Deployment's and DaemonSet's hash with the one running on the cluster and emits
 * [Event.Grafana.WorkloadConfigCompared] for each.
 */
class ConfigChangeReport(
    private val k8sService: K8sNamespaceOperations,
    private val eventBus: EventBus,
) {
    /**
     * Emits one [Event.Grafana.WorkloadConfigCompared] per Deployment and DaemonSet among [resources].
     *
     * @throws IllegalStateException if the running workloads cannot be read.
     */
    fun report(
        controlHost: ClusterHost,
        resources: List<HasMetadata>,
        namespace: String,
    ) {
        val hashes = resources.mapNotNull { resource -> workloadRef(resource)?.let { it to templateHash(resource) } }.toMap()
        if (hashes.isEmpty()) return
        val running =
            k8sService.workloadConfigHashes(controlHost, hashes.keys.toList(), namespace).getOrElse { exception ->
                error("Failed to read the running workloads' configuration hashes: ${exception.message}")
            }
        hashes.forEach { (ref, hash) ->
            eventBus.emit(Event.Grafana.WorkloadConfigCompared(ref.toString(), changed = running[ref] != hash))
        }
    }

    private fun workloadRef(resource: HasMetadata): WorkloadRef? =
        when (resource) {
            is Deployment -> WorkloadRef(WorkloadKind.Deployment, resource.metadata.name)
            is DaemonSet -> WorkloadRef(WorkloadKind.DaemonSet, resource.metadata.name)
            else -> null
        }

    private fun templateHash(resource: HasMetadata): String? {
        val template =
            when (resource) {
                is Deployment -> resource.spec?.template
                is DaemonSet -> resource.spec?.template
                else -> null
            }
        return template?.metadata?.annotations?.get(Constants.K8s.CONFIG_HASH_ANNOTATION)
    }
}
