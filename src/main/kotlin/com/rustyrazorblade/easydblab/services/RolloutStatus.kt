package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment

/** The kinds of workload whose rollout can be waited on. */
enum class WorkloadKind {
    Deployment,
    DaemonSet,
}

/** A rolled-out workload, identified by [kind] and [name]. */
data class WorkloadRef(
    val kind: WorkloadKind,
    val name: String,
) {
    override fun toString(): String = "$kind/$name"
}

/** Where a workload's rollout stands. */
sealed interface RolloutProgress {
    /** Every desired pod runs the latest template and is available. */
    data object Complete : RolloutProgress

    /** The rollout is still in flight; [reason] says what it is waiting on. */
    data class Pending(
        val reason: String,
    ) : RolloutProgress
}

/**
 * Decides whether a Deployment's or DaemonSet's rollout has finished, with the semantics of
 * `kubectl rollout status`.
 *
 * It exists because a namespace-wide pod-readiness check cannot tell a finished rollout from one
 * that has barely started: right after a rollout-restart the old pods are still Ready, and the
 * replacement pods may not exist yet. A rollout is complete only once the controller has observed
 * the latest spec (`observedGeneration` caught up to `generation`), every desired pod runs the new
 * template, no old pod remains, and every new pod is available.
 */
object RolloutStatus {
    /** Rollout progress of [deployment]; a missing `spec.replicas` means the API default of one. */
    fun of(deployment: Deployment): RolloutProgress {
        val name = "Deployment/${deployment.metadata?.name}"
        val status = deployment.status ?: return RolloutProgress.Pending("$name has no status yet")
        val desired = deployment.spec?.replicas ?: 1
        val updated = status.updatedReplicas ?: 0
        val total = status.replicas ?: 0
        val available = status.availableReplicas ?: 0
        return when {
            !observed(deployment.metadata?.generation, status.observedGeneration) ->
                RolloutProgress.Pending("$name: the restart is not yet observed by its controller")
            updated < desired ->
                RolloutProgress.Pending("$name: $updated of $desired updated replicas")
            total > updated ->
                RolloutProgress.Pending("$name: ${total - updated} old replicas are pending termination")
            available < updated ->
                RolloutProgress.Pending("$name: $available of $updated updated replicas are available")
            else -> RolloutProgress.Complete
        }
    }

    /** Rollout progress of [daemonSet] across every node it is scheduled on. */
    fun of(daemonSet: DaemonSet): RolloutProgress {
        val name = "DaemonSet/${daemonSet.metadata?.name}"
        val status = daemonSet.status ?: return RolloutProgress.Pending("$name has no status yet")
        val desired = status.desiredNumberScheduled ?: 0
        val updated = status.updatedNumberScheduled ?: 0
        val available = status.numberAvailable ?: 0
        return when {
            !observed(daemonSet.metadata?.generation, status.observedGeneration) ->
                RolloutProgress.Pending("$name: the restart is not yet observed by its controller")
            updated < desired ->
                RolloutProgress.Pending("$name: $updated of $desired updated pods")
            available < desired ->
                RolloutProgress.Pending("$name: $available of $desired updated pods are available")
            else -> RolloutProgress.Complete
        }
    }

    private fun observed(
        generation: Long?,
        observedGeneration: Long?,
    ): Boolean = generation == null || (observedGeneration != null && observedGeneration >= generation)
}
