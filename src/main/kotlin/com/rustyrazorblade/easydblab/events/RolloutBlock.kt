package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why a rollout cannot finish: a container failed, or a DaemonSet's pod is held by a node that is not Ready. */
@Serializable
enum class RolloutBlockCause {
    /** A container of a current pod is in a terminal state. */
    @SerialName("container")
    CONTAINER,

    /** A current DaemonSet pod that is not Ready is on a node that has not been Ready for longer than the grace. */
    @SerialName("node-not-ready")
    NODE_NOT_READY,

    /** An older-template DaemonSet pod is stuck terminating on a node that is not Ready, so the new pod cannot start. */
    @SerialName("stranded-pod")
    STRANDED_POD,
}

/**
 * The fact that ends a rollout wait early: which workload cannot finish, the pod and node that show
 * it, and why. It is carried by `Event.K8s.RolloutBlocked`, so a subscriber gets the fields, and
 * [describe] gives the one-line error the wait fails with.
 *
 * For [RolloutBlockCause.CONTAINER], [reason] is the container's waiting reason (`CrashLoopBackOff`,
 * `ImagePullBackOff`, ...), [message] the kubelet's message, [restarts] the restart count of a crash
 * loop, and [lastExitCode] and [lastTerminationReason] how the container last exited.
 * [otherPodsSameReason] counts the workload's other current pods failing for the same reason. For a
 * node cause, [reason] is the node's Ready status (`Ready=False`), [notReadySeconds] how long it has
 * held, and [message] the condition's reason and message.
 */
@Serializable
data class RolloutBlock(
    val workload: String,
    val pod: String,
    val node: String?,
    val cause: RolloutBlockCause,
    val reason: String,
    val container: String? = null,
    val containerType: String? = null,
    val message: String? = null,
    val restarts: Int? = null,
    val notReadySeconds: Long? = null,
    val lastExitCode: Int? = null,
    val lastTerminationReason: String? = null,
    val otherPodsSameReason: Int = 0,
) {
    /** The block as one line, naming the workload, the pod, the node and why. */
    fun describe(): String =
        when (cause) {
            RolloutBlockCause.CONTAINER -> describeContainer()
            RolloutBlockCause.NODE_NOT_READY -> "$workload: pod $pod is on node $node, which is not Ready (${nodeCondition()})"
            RolloutBlockCause.STRANDED_POD ->
                "$workload: old pod $pod is still terminating on node $node, which is not Ready (${nodeCondition()}), " +
                    "so the new pod cannot start there"
        }

    private fun describeContainer(): String {
        val details =
            listOfNotNull(
                message?.let { ": $it" },
                restarts?.let { ", after $it restarts" },
                lastExitCode?.let { code -> ", last exit $code" + (lastTerminationReason?.let { " ($it)" } ?: "") },
                " (node ${node ?: "unscheduled"})",
                (" (and $otherPodsSameReason more pods in $reason)").takeIf { otherPodsSameReason > 0 },
            )
        return "$workload: Pod $pod $containerType $container is in $reason state" + details.joinToString("")
    }

    private fun nodeCondition(): String = listOfNotNull("$reason for ${notReadySeconds}s", message).joinToString(": ")
}

/** A pod a rollout still waits on when it times out: its workload, its node and its state (phase, containers). */
@Serializable
data class UnreadyPod(
    val workload: String,
    val pod: String,
    val node: String?,
    val state: String,
) {
    /** The pod as one entry of the timeout's list. */
    fun describe(): String = "$workload pod $pod on node ${node ?: "unscheduled"} ($state)"
}
