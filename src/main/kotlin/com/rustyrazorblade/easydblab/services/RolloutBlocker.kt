package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.readiness.Readiness
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Decides whether a pending rollout can no longer finish, so the rollout wait ends at the poll that
 * sees the cause instead of at its deadline.
 *
 * It exists because a rollout's status only counts available pods: a pod that crash-loops or cannot
 * pull its image, or a DaemonSet pod pinned to a node that went NotReady, holds the count below
 * desired until the deadline, and the timeout then names no pod, node or reason. A container in a
 * terminal state is classified by [K8sPodUtils.failureOf], the same rule the namespace readiness wait
 * uses, and blocks at once. Only a DaemonSet pod that is not Ready can be blocked by its node: a Ready
 * pod already counts toward the rollout, and a Deployment pod can be replaced on another node. Its node
 * blocks only once its Ready condition has been False or Unknown for longer than
 * [Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS], measured from the condition's
 * `lastTransitionTime`, so a node that is still joining or misses a heartbeat does not fail the wait.
 * A missing or unparseable `lastTransitionTime`, or a node with no Ready condition at all, counts as
 * having just transitioned and does not block. A terminating pod is on its way out and never blocks.
 *
 * The grace is measured against the caller's clock (the operator's workstation), while
 * `lastTransitionTime` is the control plane's. It relies on both clocks being synchronised (NTP or
 * chrony); a skew of a few seconds is well within the grace, but a clock off by a minute or more would
 * shorten or lengthen it by that much.
 */
object RolloutBlocker {
    /**
     * Why [workload] cannot finish rolling out, naming the workload, the pod, its node and the reason,
     * or null when nothing in [pods] blocks it. [pods] are the workload's current pods. [node] reads a
     * node by name; it is called only for a DaemonSet, only for the nodes of its pods that are not
     * Ready, once per node, oldest pod first, and for at most [Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK]
     * nodes, so a fully Ready pod set reads no node at all. [now] is the time of the look, against
     * which a node's NotReady time is measured.
     */
    fun find(
        now: Instant,
        workload: WorkloadRef,
        pods: List<Pod>,
        node: (String) -> Node?,
    ): String? {
        val live = pods.filter { it.metadata?.deletionTimestamp == null }
        return live.firstNotNullOfOrNull { pod ->
            K8sPodUtils.failureOf(pod)?.let { "$workload: $it (node ${pod.spec?.nodeName ?: "unscheduled"})" }
        } ?: when (workload.kind) {
            WorkloadKind.DaemonSet -> onNotReadyNode(now, workload, live, node)
            WorkloadKind.Deployment -> null
        }
    }

    private fun onNotReadyNode(
        now: Instant,
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
            node(nodeName)?.let { notReadyReason(it, now) }?.let { reason ->
                "$workload: pod ${pod.metadata?.name} is on node $nodeName, which is not Ready ($reason)"
            }
        }
    }

    /**
     * Why [node] blocks a rollout at [now]: its Ready condition is not True and has not been for longer
     * than the grace period. Null when it is True, when there is no Ready condition, or when the
     * transition time is missing or unparseable (each counts as just transitioned).
     */
    private fun notReadyReason(
        node: Node,
        now: Instant,
    ): String? {
        val ready =
            node.status
                ?.conditions
                .orEmpty()
                .firstOrNull { it.type == Constants.K8s.NODE_READY_CONDITION }
                ?.takeIf { it.status != Constants.K8s.CONDITION_TRUE }
                ?: return null
        val since = parseInstant(ready.lastTransitionTime) ?: return null
        val seconds = Duration.between(since, now).seconds
        return when {
            seconds > Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS ->
                listOfNotNull("Ready=${ready.status} for ${seconds}s", ready.reason, ready.message).joinToString(": ")
            else -> null
        }
    }

    private fun parseInstant(text: String?): Instant? =
        text?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }
}
