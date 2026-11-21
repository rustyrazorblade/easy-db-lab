package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract

/**
 * Where one cluster's objects go and whose they are: the cluster [namespace] every namespaced
 * object is created in, and the [clusterId] that names cluster-scoped objects and fills the
 * ownership labels. Passed explicitly to every builder and K8s service, so no object can fall back
 * to a namespace the contract did not resolve.
 */
data class InstallScope(
    val namespace: K8sNamespace,
    val clusterId: String,
) {
    /** The ownership labels of this cluster's objects. */
    val labels: Map<String, String> get() = OwnershipLabels.of(clusterId)

    /** The cluster-scoped name of [base] for this cluster. */
    fun clusterScoped(base: String): String = ClusterScopedName.of(base, clusterId)

    companion object {
        /** The scope of [state], in the namespace its [contract] resolved. Pure. */
        fun of(
            state: ClusterState,
            contract: ClusterContract,
        ): InstallScope = InstallScope(K8sNamespace(contract.namespace), state.clusterId)
    }
}
