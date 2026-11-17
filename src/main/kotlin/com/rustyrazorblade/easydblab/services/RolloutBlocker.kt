package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.readiness.Readiness

/**
 * Decides whether a pending rollout can no longer finish, so the rollout wait ends at the poll that
 * sees the cause instead of at its deadline.
 *
 * It exists because a rollout's status only counts available pods: a pod that crash-loops or cannot
 * pull its image, or a DaemonSet pod pinned to a node that went NotReady, holds the count below
 * desired until the deadline, and the timeout then names no pod, node or reason. A container in a
 * terminal state is classified by [K8sPodUtils.failureOf], the same rule the namespace readiness wait
 * uses. Only a DaemonSet pod that is not Ready can be blocked by its node: a Ready pod already counts
 * toward the rollout, so a passing heartbeat miss on its node must not fail it, and a Deployment pod
 * can be replaced on another node. A terminating pod is on its way out and never blocks.
 */
object RolloutBlocker {
    /**
     * Why [workload] cannot finish rolling out, naming the workload, the pod, its node and the reason,
     * or null when nothing in [pods] blocks it. [pods] are the workload's current pods. [node] reads a
     * node by name; it is called only for a DaemonSet, only for the nodes of its pods that are not
     * Ready, once per node, oldest pod first, and for at most [Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK]
     * nodes, so a fully Ready pod set reads no node at all.
     */
    fun find(
        workload: WorkloadRef,
        pods: List<Pod>,
        node: (String) -> Node?,
    ): String? {
        val live = pods.filter { it.metadata?.deletionTimestamp == null }
        return live.firstNotNullOfOrNull { pod ->
            K8sPodUtils.failureOf(pod)?.let { "$workload: $it (node ${pod.spec?.nodeName ?: "unscheduled"})" }
        } ?: when (workload.kind) {
            WorkloadKind.DaemonSet -> onNotReadyNode(workload, live, node)
            WorkloadKind.Deployment -> null
        }
    }

    private fun onNotReadyNode(
        workload: WorkloadRef,
        pods: List<Pod>,
        node: (String) -> Node?,
    ): String? {
        // The oldest unready pods are the ones most likely stuck; a fresh rollout makes every pod
        // briefly unready, and the bound keeps that from becoming one node read per pod each poll.
        val podByNode =
            pods
                .filterNot(Readiness::isPodReady)
                .filter { it.spec?.nodeName != null }
                .sortedBy { it.metadata?.creationTimestamp.orEmpty() }
                .distinctBy { it.spec?.nodeName }
                .take(Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK)
        return podByNode.firstNotNullOfOrNull { pod ->
            val nodeName = pod.spec?.nodeName ?: return@firstNotNullOfOrNull null
            node(nodeName)?.let(::notReadyReason)?.let { reason ->
                "$workload: pod ${pod.metadata?.name} is on node $nodeName, which is not Ready ($reason)"
            }
        }
    }

    /** Why [node] is not Ready, or null when its Ready condition is True. */
    private fun notReadyReason(node: Node): String? {
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
}
