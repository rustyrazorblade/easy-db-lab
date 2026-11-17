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
 * means the rollout cannot finish. It reads no node list: [RolloutBlocker] asks for the nodes of a
 * pending DaemonSet's unready pods by name, and each is read at most once per look.
 */
class RolloutLooker(
    private val client: KubernetesClient,
    private val namespace: String,
) {
    /** Looks at every workload in [workloads]; the first blocked one ends the look. */
    fun look(workloads: List<WorkloadRef>): RolloutsLook {
        // A node is read by name, at most once per look, and only when RolloutBlocker asks for it.
        val nodes = mutableMapOf<String, Node?>()
        // Not getOrPut: it would treat a cached null (a node that is gone) as absent and read it again.
        val node = { name: String ->
            if (name !in nodes) nodes[name] = client.nodes().withName(name).get()
            nodes[name]
        }
        val pending = mutableListOf<String>()
        for (ref in workloads) {
            when (val state = inspect(ref, node)) {
                is RolloutsLook.Blocked -> return state
                is RolloutsLook.Pending -> pending += state.reasons
            }
        }
        return RolloutsLook.Pending(pending)
    }

    private fun inspect(
        ref: WorkloadRef,
        node: (String) -> Node?,
    ): RolloutsLook {
        val (progress, blocker) =
            when (ref.kind) {
                WorkloadKind.Deployment -> inspectDeployment(ref)
                WorkloadKind.DaemonSet -> inspectDaemonSet(ref, node)
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
        return progress to RolloutBlocker.find(ref, current) { null }
    }

    private fun inspectDaemonSet(
        ref: WorkloadRef,
        node: (String) -> Node?,
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
        return progress to RolloutBlocker.find(ref, current, node)
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
