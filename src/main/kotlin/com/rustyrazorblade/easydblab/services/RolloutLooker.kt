package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant

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
 * Takes one look at the rollouts of workloads through [reader].
 *
 * It exists so a rollout wait sees more than the controller's counters. For each workload whose
 * rollout is still pending ([RolloutStatus]), it picks the workload's pods out of the namespace by its
 * selector, keeps those of its latest template ([RolloutRevision]), and asks [RolloutBlocker] whether
 * one of them means the rollout cannot finish.
 *
 * What it reads is bounded per look, however many workloads are pending: the namespace's pods are
 * listed at most once, and only when a workload is pending; its ReplicaSets at most once, and only
 * when a Deployment is pending; and a node only when [RolloutBlocker] asks for it, by name, at most
 * once (a node that is gone too). The pods are matched to each selector in memory
 * ([LabelSelectorMatcher]), as the namespace readiness wait does.
 */
class RolloutLooker(
    private val reader: RolloutReader,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** How many looks this looker has taken; each turns the window of nodes a DaemonSet reads. */
    private var looks = 0L

    /** Looks at every workload in [workloads], in order; the first blocked one ends the look. */
    fun look(workloads: List<WorkloadRef>): RolloutsLook {
        val snapshot = Snapshot(reader, clock.instant(), looks++)
        val pending = mutableListOf<String>()
        for (ref in workloads) {
            when (val state = inspect(ref, snapshot)) {
                is RolloutsLook.Blocked -> return state
                is RolloutsLook.Pending -> pending += state.reasons
            }
        }
        return RolloutsLook.Pending(pending)
    }

    private fun inspect(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): RolloutsLook {
        val (progress, blocker) =
            when (ref.kind) {
                WorkloadKind.Deployment -> inspectDeployment(ref, snapshot)
                WorkloadKind.DaemonSet -> inspectDaemonSet(ref, snapshot)
            }
        return when {
            blocker != null -> RolloutsLook.Blocked(blocker)
            progress is RolloutProgress.Pending -> RolloutsLook.Pending(listOf(progress.reason.also { log.debug { it } }))
            else -> RolloutsLook.Pending(emptyList())
        }
    }

    private fun inspectDeployment(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): Pair<RolloutProgress, String?> {
        val deployment = reader.deployment(ref.name) ?: return notFound(ref)
        val progress = RolloutStatus.of(deployment)
        if (progress == RolloutProgress.Complete) return progress to null
        val selector = deployment.spec?.selector ?: return progress to null
        val replicaSets = snapshot.replicaSets.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val pods = snapshot.pods.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val current = RolloutRevision.currentPods(deployment, replicaSets, pods)
        return progress to RolloutBlocker.find(snapshot.now, ref, current, node = { null })
    }

    private fun inspectDaemonSet(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): Pair<RolloutProgress, String?> {
        val daemonSet = reader.daemonSet(ref.name) ?: return notFound(ref)
        val progress = RolloutStatus.of(daemonSet)
        if (progress == RolloutProgress.Complete) return progress to null
        val selector = daemonSet.spec?.selector ?: return progress to null
        val pods = snapshot.pods.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val current = RolloutRevision.currentPods(daemonSet, pods)
        val stranded = RolloutRevision.strandedPods(daemonSet, pods)
        return progress to RolloutBlocker.find(snapshot.now, ref, current, snapshot::node, stranded, snapshot.look)
    }

    private fun notFound(ref: WorkloadRef): Pair<RolloutProgress, String?> = RolloutProgress.Pending("$ref not found") to null

    /** The reads of one look, each made at most once and only when first needed. */
    private class Snapshot(
        private val reader: RolloutReader,
        /** The time this look is taken at, against which a node's NotReady time is measured. */
        val now: Instant,
        /** The number of this look, from 0, which turns the window of nodes a DaemonSet reads. */
        val look: Long,
    ) {
        val pods: List<Pod> by lazy { reader.pods() }
        val replicaSets: List<ReplicaSet> by lazy { reader.replicaSets() }
        private val nodes = mutableMapOf<String, Node?>()

        // Not getOrPut: it would treat a cached null (a node that is gone) as absent and read it again.
        fun node(name: String): Node? {
            if (name !in nodes) nodes[name] = reader.node(name)
            return nodes[name]
        }
    }
}
