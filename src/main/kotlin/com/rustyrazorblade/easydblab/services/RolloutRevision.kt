package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.ControllerRevision
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet

/**
 * Picks a workload's pods of its latest template out of every pod its selector matches.
 *
 * It exists so the rollout wait judges only the pods the rollout is waiting for. A rollout that
 * replaces crash-looping pods with a fixed template still has the old, crashing pods until they are
 * replaced, and those must not fail it. A DaemonSet labels each pod with the generation it was made
 * from and with the hash of its template's ControllerRevision; a Deployment's current pods belong to
 * the ReplicaSet at the Deployment's revision. Either way only pods the workload owns (by
 * `ownerReferences` uid) count, so another workload whose selector overlaps never fails this one.
 *
 * Until the controller has observed the workload's latest generation, no pod is current. The
 * Deployment controller moves the revision annotation only when it syncs the new spec, so right after
 * an apply the annotation still names the old ReplicaSet; judging its crashing pods then would fail
 * the very rollout that replaces them. The same guard holds for a DaemonSet, for symmetry.
 */
object RolloutRevision {
    private const val REPLICA_SET_KIND = "ReplicaSet"
    private const val DAEMON_SET_KIND = "DaemonSet"

    /**
     * The pods in [pods] that [daemonSet] owns and made from its current template. A pod is current
     * when its generation label is the template's generation (the controller's annotation, which a
     * spec change outside the template does not move; only when it is missing does
     * `metadata.generation` stand in), or when its `controller-revision-hash` is the hash of the
     * DaemonSet's current ControllerRevision, the highest revision among [revisions] it owns. The hash
     * catches a template set back to an earlier one (A, then B, then A again): the controller reuses
     * A's ControllerRevision and keeps a node's A pod, whose generation label still names the first A.
     * Empty until the controller observed the latest generation.
     */
    fun currentPods(
        daemonSet: DaemonSet,
        revisions: List<ControllerRevision>,
        pods: List<Pod>,
    ): List<Pod> {
        val isCurrent = currentTest(daemonSet, revisions) ?: return emptyList()
        return ownedBy(daemonSet, pods).filter(isCurrent)
    }

    /**
     * The terminating pods in [pods] of an older template of [daemonSet], each on a node that has no
     * pod of the current template. A rolling update deletes a node's old pod before it creates the
     * new one, so on a node that stopped answering the old pod never goes and the new one never
     * comes: only this pod points at the node. Empty until the controller observed the latest
     * generation, as for [currentPods].
     */
    fun strandedPods(
        daemonSet: DaemonSet,
        revisions: List<ControllerRevision>,
        pods: List<Pod>,
    ): List<Pod> {
        val isCurrent = currentTest(daemonSet, revisions) ?: return emptyList()
        val owned = ownedBy(daemonSet, pods)
        val nodesWithCurrent = owned.filter(isCurrent).mapNotNull { it.spec?.nodeName }.toSet()
        return owned.filter { pod ->
            pod.metadata?.deletionTimestamp != null &&
                !isCurrent(pod) &&
                pod.spec?.nodeName.let { it != null && it !in nodesWithCurrent }
        }
    }

    /** Whether a pod is of [daemonSet]'s current template, or null when the controller lags its spec. */
    private fun currentTest(
        daemonSet: DaemonSet,
        revisions: List<ControllerRevision>,
    ): ((Pod) -> Boolean)? {
        val generation = currentGeneration(daemonSet) ?: return null
        val hash = currentRevisionHash(daemonSet, revisions)
        return { pod -> generationOf(pod) == generation || (hash != null && hashOf(pod) == hash) }
    }

    private fun currentRevisionHash(
        daemonSet: DaemonSet,
        revisions: List<ControllerRevision>,
    ): String? {
        val uid = daemonSet.metadata?.uid ?: return null
        return revisions
            .filter { isOwnedBy(it.metadata?.ownerReferences, DAEMON_SET_KIND, uid) }
            .maxByOrNull { it.revision ?: 0L }
            ?.metadata
            ?.labels
            ?.get(Constants.K8s.CONTROLLER_REVISION_HASH_LABEL)
    }

    private fun ownedBy(
        daemonSet: DaemonSet,
        pods: List<Pod>,
    ): List<Pod> {
        val uid = daemonSet.metadata?.uid ?: return emptyList()
        return pods.filter { isOwnedBy(it.metadata?.ownerReferences, DAEMON_SET_KIND, uid) }
    }

    private fun isOwnedBy(
        owners: List<OwnerReference>?,
        kind: String,
        uid: String,
    ): Boolean = owners.orEmpty().any { it.kind == kind && it.uid == uid }

    private fun hashOf(pod: Pod): String? = pod.metadata?.labels?.get(Constants.K8s.CONTROLLER_REVISION_HASH_LABEL)

    private fun currentGeneration(daemonSet: DaemonSet): String? {
        if (!RolloutStatus.observed(daemonSet.metadata?.generation, daemonSet.status?.observedGeneration)) return null
        return daemonSet.metadata
            ?.annotations
            ?.get(Constants.K8s.DAEMONSET_TEMPLATE_GENERATION_ANNOTATION)
            ?: daemonSet.metadata?.generation?.toString()
    }

    private fun generationOf(pod: Pod): String? = pod.metadata?.labels?.get(Constants.K8s.POD_TEMPLATE_GENERATION_LABEL)

    /**
     * The pods in [pods] owned by the ReplicaSet of [deployment]'s current revision, found among
     * [replicaSets]. Empty while the controller has not yet given the Deployment a revision, or has
     * not yet observed its latest generation.
     */
    fun currentPods(
        deployment: Deployment,
        replicaSets: List<ReplicaSet>,
        pods: List<Pod>,
    ): List<Pod> {
        if (!RolloutStatus.observed(deployment.metadata?.generation, deployment.status?.observedGeneration)) return emptyList()
        val revision = revisionOf(deployment.metadata?.annotations) ?: return emptyList()
        val uid = deployment.metadata?.uid ?: return emptyList()
        val current =
            replicaSets
                .filter { rs ->
                    rs.metadata
                        ?.ownerReferences
                        .orEmpty()
                        .any { it.uid == uid }
                }.filter { revisionOf(it.metadata?.annotations) == revision }
                .mapNotNull { it.metadata?.uid }
                .toSet()
        return pods.filter { pod -> current.any { isOwnedBy(pod.metadata?.ownerReferences, REPLICA_SET_KIND, it) } }
    }

    private fun revisionOf(annotations: Map<String, String>?): String? = annotations?.get(Constants.K8s.DEPLOYMENT_REVISION_ANNOTATION)
}
