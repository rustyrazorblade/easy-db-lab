package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.RolloutBlock
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.ControllerRevision
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.readiness.Readiness
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

/** A pod [workload]'s rollout still waits on: a current pod that is not Ready, or a stranded old one. */
data class WaitingPod(
    val workload: WorkloadRef,
    val pod: Pod,
)

/** One look at a set of rollouts: one of them cannot finish, or what each unfinished one waits on. */
sealed interface RolloutsLook {
    /** A rollout cannot finish; [block] names the workload, the pod, the node and why. */
    data class Blocked(
        val block: RolloutBlock,
    ) : RolloutsLook

    /**
     * What each unfinished workload is waiting on ([reasons]; empty once every rollout is complete), the
     * [workloads] still pending, and the pods they wait on ([unready]: at most
     * [Constants.K8s.ROLLOUT_UNREADY_PODS_LISTED_PER_WORKLOAD] per workload, and [unreadyOmitted] more).
     */
    data class Pending(
        val reasons: List<String>,
        val workloads: List<WorkloadRef> = emptyList(),
        val unready: List<WaitingPod> = emptyList(),
        val unreadyOmitted: Int = 0,
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
 * once (a node that is gone too); its ControllerRevisions at most once, and only when a DaemonSet is
 * pending. The pods are matched to each selector in memory ([LabelSelectorMatcher]), so the
 * Kubernetes API is never asked for one list per workload.
 */
class RolloutLooker(
    private val reader: RolloutReader,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * The last node read for each DaemonSet, by name, so its next look reads on from there
     * ([RolloutBlocker.find]).
     */
    private val lastNodeRead = mutableMapOf<String, String>()

    /** Looks at every workload in [workloads], in order; the first blocked one ends the look. */
    fun look(workloads: List<WorkloadRef>): RolloutsLook {
        val snapshot = Snapshot(reader, clock.instant())
        val pending = mutableListOf<Pair<RolloutProgress.Pending, Inspection>>()
        for (ref in workloads) {
            val inspection = inspect(ref, snapshot)
            inspection.block?.let { return RolloutsLook.Blocked(it) }
            when (val progress = inspection.progress) {
                is RolloutProgress.Pending -> pending += progress to inspection.also { log.debug { progress.reason } }
                RolloutProgress.Complete -> Unit
            }
        }
        return pendingLook(pending)
    }

    private fun pendingLook(pending: List<Pair<RolloutProgress.Pending, Inspection>>): RolloutsLook.Pending {
        val cap = Constants.K8s.ROLLOUT_UNREADY_PODS_LISTED_PER_WORKLOAD
        val inspections = pending.map { it.second }
        return RolloutsLook.Pending(
            reasons = pending.map { it.first.reason },
            workloads = inspections.map { it.ref },
            unready = inspections.flatMap { inspection -> inspection.waiting.take(cap).map { WaitingPod(inspection.ref, it) } },
            unreadyOmitted = inspections.sumOf { (it.waiting.size - cap).coerceAtLeast(0) },
        )
    }

    /** What one look found for [ref]: its [progress], what blocks it, and the pods it is [waiting] on. */
    private data class Inspection(
        val ref: WorkloadRef,
        val progress: RolloutProgress,
        val block: RolloutBlock? = null,
        val waiting: List<Pod> = emptyList(),
    )

    private fun inspect(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): Inspection =
        when (ref.kind) {
            WorkloadKind.Deployment -> inspectDeployment(ref, snapshot)
            WorkloadKind.DaemonSet -> inspectDaemonSet(ref, snapshot)
        }

    private fun inspectDeployment(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): Inspection {
        val deployment = reader.deployment(ref.name) ?: return notFound(ref)
        val progress = RolloutStatus.of(deployment)
        if (progress == RolloutProgress.Complete) return Inspection(ref, progress)
        val selector = deployment.spec?.selector ?: return Inspection(ref, progress)
        val replicaSets = snapshot.replicaSets.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val pods = snapshot.pods.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val current = RolloutRevision.currentPods(deployment, replicaSets, pods)
        val block = RolloutBlocker.find(snapshot.now, ref, current, node = { null })
        return Inspection(ref, progress, block, unreadyOf(current))
    }

    private fun inspectDaemonSet(
        ref: WorkloadRef,
        snapshot: Snapshot,
    ): Inspection {
        val daemonSet = reader.daemonSet(ref.name) ?: return notFound(ref)
        val progress = RolloutStatus.of(daemonSet)
        if (progress == RolloutProgress.Complete) return Inspection(ref, progress)
        val selector = daemonSet.spec?.selector ?: return Inspection(ref, progress)
        val pods = snapshot.pods.filter { LabelSelectorMatcher.matches(selector, it.metadata?.labels) }
        val current = RolloutRevision.currentPods(daemonSet, snapshot.controllerRevisions, pods)
        val stranded = RolloutRevision.strandedPods(daemonSet, snapshot.controllerRevisions, pods)
        val node = { name: String -> snapshot.node(name).also { lastNodeRead[ref.name] = name } }
        val block = RolloutBlocker.find(snapshot.now, ref, current, node, stranded, lastNodeRead[ref.name])
        return Inspection(ref, progress, block, unreadyOf(current) + stranded)
    }

    private fun unreadyOf(current: List<Pod>): List<Pod> =
        current.filter { it.metadata?.deletionTimestamp == null && !Readiness.isPodReady(it) }

    private fun notFound(ref: WorkloadRef): Inspection = Inspection(ref, RolloutProgress.Pending("$ref not found"))

    /** The reads of one look, each made at most once and only when first needed. */
    private class Snapshot(
        private val reader: RolloutReader,
        /** The time this look is taken at, against which a node's NotReady time is measured. */
        val now: Instant,
    ) {
        val pods: List<Pod> by lazy { reader.pods() }
        val replicaSets: List<ReplicaSet> by lazy { reader.replicaSets() }
        val controllerRevisions: List<ControllerRevision> by lazy { reader.controllerRevisions() }
        private val nodes = mutableMapOf<String, Node?>()

        // Not getOrPut: it would treat a cached null (a node that is gone) as absent and read it again.
        fun node(name: String): Node? {
            if (name !in nodes) nodes[name] = reader.node(name)
            return nodes[name]
        }
    }
}
