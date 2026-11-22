package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract

/**
 * The one lookup of a role's node pool: the nodes that match the role's selector, read from the
 * Kubernetes API rather than the hosts `state.json` records, which are empty on a cluster
 * easy-db-lab did not build. An unconstrained role's pool is every node. It also reads what the
 * db pool's smallest node can give a pod, so a kit can size its database pods to fit every node.
 */
class RoleNodes(
    private val k8sService: K8sService,
) {
    /**
     * How many nodes match [selector].
     *
     * @throws IllegalStateException naming the selector when the nodes cannot be listed
     */
    fun count(selector: NodeSelector): Int =
        k8sService
            .nodesMatching(selector.labels)
            .getOrElse { e ->
                throw IllegalStateException("Could not list the nodes matching '${selector.describe()}': ${e.message}", e)
            }.size

    /**
     * The placement variables for [state]'s role selectors: each role's pool counted from the
     * cluster, the db pool's smallest allocatable resources, and whether the contract asks for
     * Guaranteed pods.
     *
     * @throws IllegalStateException naming the db selector when its nodes' resources cannot be read
     */
    fun placement(state: ClusterState): KitPlacementVariables {
        val selectors = RoleSelectors.from(state)
        val allocatable =
            k8sService.nodeAllocatable(selectors.db.labels).getOrElse { e ->
                throw IllegalStateException(
                    "Could not read the allocatable resources of the nodes matching '${selectors.db.describe()}': ${e.message}",
                    e,
                )
            }
        return KitPlacementVariables(
            selectors = selectors,
            dbNodeCount = count(selectors.db),
            appNodeCount = count(selectors.app),
            dbNodeAllocatable = NodeAllocatable.smallest(allocatable),
            guaranteedPods = ClusterContract.of(state).guaranteedPods,
        )
    }
}
