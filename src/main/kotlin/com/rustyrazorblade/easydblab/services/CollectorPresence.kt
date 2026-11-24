package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope

/**
 * Whether the observability stack is deployed in a cluster, as the cluster itself tells it: the core
 * collector DaemonSet exists in the install namespace with the config hash every deploy stamps on it.
 *
 * A kit start re-syncs the collector only where the stack is deployed. Anywhere else, applying the
 * collector would create it (its DaemonSet on every node, its cluster-wide RBAC) on a cluster with no
 * `cluster-config` and no backends, where its pods can never start, and a kit stop would leave it
 * behind. No flag records the deploy: the cluster is the source of truth, read through the same
 * hash reader [ConfigChangeReport] uses.
 */
class CollectorPresence(
    private val k8sService: K8sNamespaceOperations,
) {
    private val collector = WorkloadRef(WorkloadKind.DaemonSet, Constants.OtelCollector.SERVICE_NAME)

    /**
     * True when the collector is deployed in [scope]'s namespace.
     *
     * @throws IllegalStateException if the cluster cannot be read.
     */
    fun isDeployed(scope: InstallScope): Boolean {
        val hashes =
            k8sService.workloadConfigHashes(listOf(collector), scope.namespace.value).getOrElse { exception ->
                throw IllegalStateException("Failed to read whether the OTel collector is deployed: ${exception.message}", exception)
            }
        return hashes[collector] != null
    }
}
