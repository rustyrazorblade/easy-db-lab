package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod

/**
 * Decides whether a pending rollout can no longer finish, so the rollout wait ends at the poll that
 * sees the cause instead of at its deadline.
 *
 * It exists because a rollout's status only counts available pods: a pod that crash-loops or cannot
 * pull its image, or a DaemonSet pod pinned to a node that went NotReady, holds the count below
 * desired until the deadline, and the timeout then names no pod, node or reason. A container in a
 * terminal state is classified by [K8sPodUtils.failureOf], the same rule the namespace readiness wait
 * uses. A Deployment pod on a NotReady node is not a blocker, since the controller can place a
 * replacement elsewhere; a DaemonSet pod cannot move. A terminating pod is on its way out and never
 * blocks.
 */
object RolloutBlocker {
    /**
     * Why [workload] cannot finish rolling out, naming the workload, the pod, its node and the reason,
     * or null when nothing in [pods] blocks it. [pods] are the workload's current pods; [nodes] are the
     * cluster's nodes, consulted for a DaemonSet only.
     */
    fun find(
        workload: WorkloadRef,
        pods: List<Pod>,
        nodes: List<Node>,
    ): String? {
        val live = pods.filter { it.metadata?.deletionTimestamp == null }
        return live.firstNotNullOfOrNull { pod ->
            K8sPodUtils.failureOf(pod)?.let { "$workload: $it (node ${nodeOf(pod)})" }
        } ?: when (workload.kind) {
            WorkloadKind.DaemonSet -> onNotReadyNode(workload, live, nodes)
            WorkloadKind.Deployment -> null
        }
    }

    private fun onNotReadyNode(
        workload: WorkloadRef,
        pods: List<Pod>,
        nodes: List<Node>,
    ): String? {
        val byName = nodes.associateBy { it.metadata?.name }
        return pods.firstNotNullOfOrNull { pod ->
            val node = pod.spec?.nodeName?.let(byName::get) ?: return@firstNotNullOfOrNull null
            readyCondition(node)?.let { reason ->
                "$workload: pod ${pod.metadata?.name} is on node ${node.metadata?.name}, which is not Ready ($reason)"
            }
        }
    }

    /** Why [node] is not Ready, or null when its Ready condition is True. */
    private fun readyCondition(node: Node): String? {
        val ready =
            node.status
                ?.conditions
                .orEmpty()
                .firstOrNull { it.type == Constants.K8s.NODE_READY_CONDITION }
                ?: return "no Ready condition"
        return when (ready.status) {
            Constants.K8s.CONDITION_TRUE -> null
            else -> listOfNotNull("Ready=${ready.status}", ready.reason, ready.message).joinToString(": ")
        }
    }

    private fun nodeOf(pod: Pod): String = pod.spec?.nodeName ?: "unscheduled"
}
