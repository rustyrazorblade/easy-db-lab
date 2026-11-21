package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Placement

/**
 * Refuses a placement no node can take.
 *
 * `init --node-label` adds labels to the backends' node selector but labels no node. A label no node
 * carries would leave every backend and Grafana Pending until the rollout wait times out, so the
 * deploy lists the nodes that match the whole selector first and fails, naming the selector, before
 * it applies anything.
 */
class PlacementCheck(
    private val k8sService: K8sService,
) {
    /** Fails unless at least one node carries every label in [placement]'s node selector. */
    fun require(placement: Placement) {
        val selector = placement.nodeSelector
        val nodes =
            k8sService.nodesMatching(selector).getOrElse { exception ->
                throw IllegalStateException("Could not list the nodes for the observability placement: ${exception.message}", exception)
            }
        check(nodes.isNotEmpty()) {
            val labels = selector.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "No node carries every label the observability backends and Grafana are placed by: $labels. " +
                "init --node-label only adds to the selector; label a node first, e.g. kubectl label node <node> key=value."
        }
    }
}
