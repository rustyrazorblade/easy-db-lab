package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet

/**
 * Picks a workload's pods of its latest template out of every pod its selector matches.
 *
 * It exists so the rollout wait judges only the pods the rollout is waiting for. A rollout that
 * replaces crash-looping pods with a fixed template still has the old, crashing pods until they are
 * replaced, and those must not fail it. A DaemonSet labels each pod with the generation it was made
 * from; a Deployment's current pods belong to the ReplicaSet at the Deployment's revision.
 *
 * Until the controller has observed the workload's latest generation, no pod is current. The
 * Deployment controller moves the revision annotation only when it syncs the new spec, so right after
 * an apply the annotation still names the old ReplicaSet; judging its crashing pods then would fail
 * the very rollout that replaces them. The same guard holds for a DaemonSet, for symmetry.
 */
object RolloutRevision {
    private const val REPLICA_SET_KIND = "ReplicaSet"

    /**
     * The pods in [pods] made from [daemonSet]'s current template. The template's generation is the
     * controller's annotation, which a spec change outside the template does not move; only when it is
     * missing does `metadata.generation` stand in. Empty until the controller observed the latest generation.
     */
    fun currentPods(
        daemonSet: DaemonSet,
        pods: List<Pod>,
    ): List<Pod> {
        if (!RolloutStatus.observed(daemonSet.metadata?.generation, daemonSet.status?.observedGeneration)) return emptyList()
        val generation =
            daemonSet.metadata
                ?.annotations
                ?.get(Constants.K8s.DAEMONSET_TEMPLATE_GENERATION_ANNOTATION)
                ?: daemonSet.metadata?.generation?.toString()
                ?: return emptyList()
        return pods.filter { it.metadata?.labels?.get(Constants.K8s.POD_TEMPLATE_GENERATION_LABEL) == generation }
    }

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
        return pods.filter { pod ->
            pod.metadata
                ?.ownerReferences
                .orEmpty()
                .any { it.kind == REPLICA_SET_KIND && it.uid in current }
        }
    }

    private fun revisionOf(annotations: Map<String, String>?): String? = annotations?.get(Constants.K8s.DEPLOYMENT_REVISION_ANNOTATION)
}
