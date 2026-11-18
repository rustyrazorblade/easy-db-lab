package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.RolloutBlock
import com.rustyrazorblade.easydblab.events.RolloutBlockCause
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
     * Why [workload] cannot finish rolling out ([RolloutBlock]: the workload, the pod, its node and the
     * reason), or null when nothing in [pods] blocks it. [pods] are the workload's current pods. [node] reads a
     * node by name; it is called only for a DaemonSet, only for the nodes of its pods that are not
     * Ready, once per node, and for at most [Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK] nodes, so a fully
     * Ready pod set reads no node at all. [now] is the time of the look, against which a node's NotReady
     * time is measured. [stranded] are a DaemonSet's terminating older-template pods on nodes with no
     * current pod; their nodes share the same bound with the unready pods' nodes.
     *
     * When more nodes are candidates than the bound, [after] (the name of the last node the previous look
     * read for this workload, null on the first) picks which are read: the first nodes, by name, that sort
     * after it, wrapping round to the start. A look that does not read a given candidate node reads the
     * bound's worth of nodes between [after] and it, by name, and moves [after] on to the last of them, so
     * no two looks read the same node before they reach it. A node that stays a candidate is therefore read
     * within `ceil(S / bound)` looks, where S is the number of distinct nodes that were candidates in any
     * of them, however the other pods change between looks (becoming Ready, or new ones being scheduled).
     */
    fun find(
        now: Instant,
        workload: WorkloadRef,
        pods: List<Pod>,
        node: (String) -> Node?,
        stranded: List<Pod> = emptyList(),
        after: String? = null,
    ): RolloutBlock? {
        val live = pods.filter { it.metadata?.deletionTimestamp == null }
        return containerBlock(workload, live)
            ?: when (workload.kind) {
                WorkloadKind.DaemonSet -> onNotReadyNode(now, workload, live.filterNot(Readiness::isPodReady) + stranded, node, after)
                WorkloadKind.Deployment -> null
            }
    }

    /**
     * The terminal failure of [pod] ([K8sPodUtils.failingContainer]), judged without its containers in
     * an early crash loop, so one that has not crashed often enough neither blocks nor hides another
     * container's failure. A crash loop carries its restart count, and every failure how its container
     * last exited and how many of the other [live] pods fail for the same reason.
     */
    private fun containerBlock(
        workload: WorkloadRef,
        live: List<Pod>,
    ): RolloutBlock? {
        val failures = live.mapNotNull { pod -> failureOf(pod)?.let { pod to it } }
        val (pod, failure) = failures.firstOrNull() ?: return null
        val lastExit = failure.status.lastState?.terminated
        return RolloutBlock(
            workload = workload.toString(),
            pod = pod.metadata?.name ?: "unknown",
            node = pod.spec?.nodeName,
            cause = RolloutBlockCause.CONTAINER,
            reason = failure.reason,
            container = failure.container,
            containerType = failure.containerType,
            message =
                failure.status.state
                    ?.waiting
                    ?.message,
            restarts = (failure.status.restartCount ?: 0).takeIf { failure.reason == Constants.K8s.CRASH_LOOP_BACK_OFF },
            lastExitCode = lastExit?.exitCode,
            lastTerminationReason = lastExit?.reason,
            otherPodsSameReason = failures.drop(1).count { it.second.reason == failure.reason },
        )
    }

    private fun failureOf(pod: Pod): ContainerFailure? = K8sPodUtils.failingContainer(pod) { !isEarlyCrashLoop(it) }

    private fun isEarlyCrashLoop(status: ContainerStatus): Boolean =
        status.state?.waiting?.reason == Constants.K8s.CRASH_LOOP_BACK_OFF &&
            (status.restartCount ?: 0) < Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS

    private fun onNotReadyNode(
        now: Instant,
        workload: WorkloadRef,
        waiting: List<Pod>,
        node: (String) -> Node?,
        after: String?,
    ): RolloutBlock? {
        // [waiting] are the unready current pods and the stranded old ones. A fresh rollout makes every
        // pod briefly unready, and the bound keeps that from becoming one node read per pod each poll.
        val candidates =
            waiting
                .filter { it.spec?.nodeName != null }
                .sortedBy { it.spec?.nodeName.orEmpty() }
                .distinctBy { it.spec?.nodeName }
        return window(candidates, after).firstNotNullOfOrNull { pod ->
            val nodeName = pod.spec?.nodeName ?: return@firstNotNullOfOrNull null
            node(nodeName)?.let { notReady(it, now) }?.let { notReady ->
                RolloutBlock(
                    workload = workload.toString(),
                    pod = pod.metadata?.name ?: "unknown",
                    node = nodeName,
                    cause =
                        when (pod.metadata?.deletionTimestamp) {
                            null -> RolloutBlockCause.NODE_NOT_READY
                            else -> RolloutBlockCause.STRANDED_POD
                        },
                    reason = "Ready=${notReady.status}",
                    message = notReady.detail,
                    notReadySeconds = notReady.seconds,
                )
            }
        }
    }

    /** A node's Ready condition that is not True: its [status], for how many [seconds], and the condition's [detail]. */
    private data class NotReady(
        val status: String,
        val seconds: Long,
        val detail: String?,
    )

    /**
     * The first [Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK] of [candidates] (sorted by node name) whose
     * node sorts after [after], wrapping round to the start.
     */
    private fun window(
        candidates: List<Pod>,
        after: String?,
    ): List<Pod> {
        val (behind, ahead) = candidates.partition { after != null && it.spec?.nodeName.orEmpty() <= after }
        return (ahead + behind).take(Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK)
    }

    /**
     * Why [node] blocks a rollout at [now]: its Ready condition is not True and has not been for longer
     * than the grace period. Null when it is True, when there is no Ready condition, or when the
     * transition time is missing or unparseable (each counts as just transitioned).
     */
    private fun notReady(
        node: Node,
        now: Instant,
    ): NotReady? {
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
                NotReady(ready.status, seconds, listOfNotNull(ready.reason, ready.message).joinToString(": ").ifEmpty { null })
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
