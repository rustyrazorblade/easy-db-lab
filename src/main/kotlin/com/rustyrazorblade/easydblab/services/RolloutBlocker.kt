package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.ContainerStatus
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
 * uses, and blocks at once, with one exception: the kubelet reports `CrashLoopBackOff` after the first
 * crash, and a pod may crash once or twice while a dependency comes up, so a container in
 * `CrashLoopBackOff` blocks only once its `restartCount` reaches
 * [Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS]. An image pull failure, or any other terminal
 * reason, still blocks at once. Only a DaemonSet pod that is not Ready can be blocked by its node: a Ready
 * pod already counts toward the rollout, and a Deployment pod can be replaced on another node. Its node
 * blocks only once its Ready condition has been False or Unknown for longer than
 * [Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS], measured from the condition's
 * `lastTransitionTime`, so a node that is still joining or misses a heartbeat does not fail the wait.
 * A missing or unparseable `lastTransitionTime`, or a node with no Ready condition at all, counts as
 * having just transitioned and does not block. A terminating pod is on its way out and never blocks
 * through its containers. It does point at its node when it is a DaemonSet's older-template pod on a
 * node with no current pod ([RolloutRevision.strandedPods]): a rolling update waits for it to go before
 * it creates the new pod there, so a node that stopped answering holds the rollout with no current pod
 * on it. Its node is judged by the same grace.
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
     * which a node's NotReady time is measured. [stranded] are a DaemonSet's terminating older-template
     * pods on nodes with no current pod; their nodes share the same bound with the unready pods' nodes.
     */
    fun find(
        now: Instant,
        workload: WorkloadRef,
        pods: List<Pod>,
        node: (String) -> Node?,
        stranded: List<Pod> = emptyList(),
    ): String? {
        val live = pods.filter { it.metadata?.deletionTimestamp == null }
        return live.firstNotNullOfOrNull { pod ->
            containerFailure(pod)?.let { "$workload: $it (node ${pod.spec?.nodeName ?: "unscheduled"})" }
        } ?: when (workload.kind) {
            WorkloadKind.DaemonSet -> onNotReadyNode(now, workload, live.filterNot(Readiness::isPodReady) + stranded, node)
            WorkloadKind.Deployment -> null
        }
    }

    /**
     * The terminal failure of [pod] ([K8sPodUtils.failingContainer]), judged without its containers in
     * an early crash loop, so one that has not crashed often enough neither blocks nor hides another
     * container's failure. A crash loop's reason carries its restart count.
     */
    private fun containerFailure(pod: Pod): String? {
        val failure = K8sPodUtils.failingContainer(pod) { !isEarlyCrashLoop(it) } ?: return null
        return when (failure.reason) {
            CRASH_LOOP_REASON -> "${failure.description}, after ${failure.status.restartCount ?: 0} restarts"
            else -> failure.description
        }
    }

    private fun isEarlyCrashLoop(status: ContainerStatus): Boolean =
        status.state?.waiting?.reason == CRASH_LOOP_REASON &&
            (status.restartCount ?: 0) < Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS

    private const val CRASH_LOOP_REASON = "CrashLoopBackOff"

    private fun onNotReadyNode(
        now: Instant,
        workload: WorkloadRef,
        waiting: List<Pod>,
        node: (String) -> Node?,
    ): String? {
        // [waiting] are the unready current pods and the stranded old ones. The oldest are the ones
        // most likely stuck; a fresh rollout makes every pod briefly unready, and the bound keeps that
        // from becoming one node read per pod each poll.
        val podByNode =
            waiting
                .filter { it.spec?.nodeName != null }
                .sortedBy { it.metadata?.creationTimestamp.orEmpty() }
                .distinctBy { it.spec?.nodeName }
                .take(Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK)
        return podByNode.firstNotNullOfOrNull { pod ->
            val nodeName = pod.spec?.nodeName ?: return@firstNotNullOfOrNull null
            node(nodeName)?.let { notReadyReason(it, now) }?.let { reason ->
                when (pod.metadata?.deletionTimestamp) {
                    null -> "$workload: pod ${pod.metadata?.name} is on node $nodeName, which is not Ready ($reason)"
                    else ->
                        "$workload: old pod ${pod.metadata?.name} is still terminating on node $nodeName, which is " +
                            "not Ready ($reason), so the new pod cannot start there"
                }
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
