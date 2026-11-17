package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** One look at a set of rollouts: one of them cannot finish, or what each unfinished one waits on. */
sealed interface RolloutsLook {
    /** A rollout cannot finish; [reason] names the workload, the pod, the node and why. */
    data class Blocked(
        val reason: String,
    ) : RolloutsLook

    /** What each unfinished workload is waiting on; empty once every rollout is complete. */
    data class Pending(
        val reasons: List<String>,
    ) : RolloutsLook

    /** Whether a wait can stop on this look: something blocks, or nothing is left pending. */
    fun isFinal(): Boolean =
        when (this) {
            is Blocked -> true
            is Pending -> reasons.isEmpty()
        }
}

/**
 * Takes one look at the rollouts of workloads in [namespace] through [client].
 *
 * It exists so a rollout wait sees more than the controller's counters. For each workload whose
 * rollout is still pending ([RolloutStatus]), it lists the workload's pods by its selector, keeps
 * those of its latest template ([RolloutRevision]), and asks [RolloutBlocker] whether one of them
 * means the rollout cannot finish. Nodes are listed at most once per look, and only when a DaemonSet
 * is pending.
 */
class RolloutLooker(
    private val client: KubernetesClient,
    private val namespace: String,
) {
    /** Looks at every workload in [workloads]; the first blocked one ends the look. */
    fun look(workloads: List<WorkloadRef>): RolloutsLook {
        val nodes by lazy { client.nodes().list().items }
        val pending = mutableListOf<String>()
        for (ref in workloads) {
            when (val state = inspect(ref) { nodes }) {
                is RolloutsLook.Blocked -> return state
                is RolloutsLook.Pending -> pending += state.reasons
            }
        }
        return RolloutsLook.Pending(pending)
    }

    private fun inspect(
        ref: WorkloadRef,
        nodes: () -> List<Node>,
    ): RolloutsLook {
        val (progress, blocker) =
            when (ref.kind) {
                WorkloadKind.Deployment -> inspectDeployment(ref)
                WorkloadKind.DaemonSet -> inspectDaemonSet(ref, nodes)
            }
        return when {
            blocker != null -> RolloutsLook.Blocked(blocker)
            progress is RolloutProgress.Pending -> RolloutsLook.Pending(listOf(progress.reason.also { log.debug { it } }))
            else -> RolloutsLook.Pending(emptyList())
        }
    }

    private fun inspectDeployment(ref: WorkloadRef): Pair<RolloutProgress, String?> {
        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .withName(ref.name)
                .get() ?: return notFound(ref)
        val progress = RolloutStatus.of(deployment)
        if (progress == RolloutProgress.Complete) return progress to null
        val selector = deployment.spec?.selector ?: return progress to null
        val replicaSets =
            client
                .apps()
                .replicaSets()
                .inNamespace(namespace)
                .withLabelSelector(selector)
                .list()
                .items
        val current = RolloutRevision.currentPods(deployment, replicaSets, pods(selector))
        return progress to RolloutBlocker.find(ref, current, emptyList())
    }

    private fun inspectDaemonSet(
        ref: WorkloadRef,
        nodes: () -> List<Node>,
    ): Pair<RolloutProgress, String?> {
        val daemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(namespace)
                .withName(ref.name)
                .get() ?: return notFound(ref)
        val progress = RolloutStatus.of(daemonSet)
        if (progress == RolloutProgress.Complete) return progress to null
        val selector = daemonSet.spec?.selector ?: return progress to null
        val current = RolloutRevision.currentPods(daemonSet, pods(selector))
        return progress to RolloutBlocker.find(ref, current, nodes())
    }

    private fun pods(selector: LabelSelector): List<Pod> =
        client
            .pods()
            .inNamespace(namespace)
            .withLabelSelector(selector)
            .list()
            .items

    private fun notFound(ref: WorkloadRef): Pair<RolloutProgress, String?> = RolloutProgress.Pending("$ref not found") to null
}
