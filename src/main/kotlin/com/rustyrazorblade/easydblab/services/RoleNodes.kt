package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors

/**
 * The one lookup of a role's node pool: the nodes that match the role's selector, read from the
 * Kubernetes API rather than the hosts `state.json` records, which are empty on a cluster
 * easy-db-lab did not build. An unconstrained role's pool is every node.
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
                throw IllegalStateException("Could not list the nodes matching '${describe(selector)}': ${e.message}", e)
            }.size

    /** The placement variables for [selectors], with each role's pool counted from the cluster. */
    fun placement(selectors: RoleSelectors): KitPlacementVariables =
        KitPlacementVariables(selectors, count(selectors.db), count(selectors.app))

    private fun describe(selector: NodeSelector): String = selector.toLabelSelector().ifEmpty { "<any node>" }
}
