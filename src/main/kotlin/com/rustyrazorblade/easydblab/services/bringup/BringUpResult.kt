package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException

/**
 * Every port the bring-up coordinator works through (design D12), grouped so `NodeBringUp` takes
 * one value instead of a long constructor. None of them is a provider or K3s type.
 */
data class BringUpPortSet(
    val tracker: InstanceTracker,
    val fleet: InstanceFleet,
    val nodeSteps: NodeSteps,
    val controlSteps: ControlNodeSteps,
    val state: ClusterStateWriter,
)

/** How one database or application node's pipeline ended, on its last instance [host]. */
sealed interface NodeEnd {
    val host: ClusterHost
    val replacements: Int

    /** The node is a Ready cluster node. */
    data class Ready(
        override val host: ClusterHost,
        override val replacements: Int,
    ) : NodeEnd

    /** `--no-setup`: the node answers over SSH, and its pipeline ended there. */
    data class Provisioned(
        override val host: ClusterHost,
        override val replacements: Int,
    ) : NodeEnd

    /**
     * The node could not be brought up: [step] failed for [reason]. Its last instance [host] is
     * kept for debugging, unless [instanceKept] is false: that instance was terminated for a
     * replacement whose capacity check or launch then failed.
     */
    data class Failed(
        override val host: ClusterHost,
        override val replacements: Int,
        val step: PipelineStep,
        val reason: String,
        val instanceKept: Boolean = true,
    ) : NodeEnd
}

/** Every database and application node's end, by alias. */
data class BringUpResult(
    val nodes: Map<String, NodeEnd>,
) {
    /** The nodes that failed, in alias order. */
    val failed: List<NodeEnd.Failed>
        get() = nodes.values.filterIsInstance<NodeEnd.Failed>().sortedBy { it.host.alias }

    /** Whether [alias] runs on a new instance. */
    fun wasReplaced(alias: String): Boolean = (nodes[alias]?.replacements ?: 0) > 0

    /** The event naming every failed node, or null when none failed. */
    fun failureEvent(): Event.Provision.NodesFailed? =
        failed.takeIf { it.isNotEmpty() }?.let { nodes ->
            Event.Provision.NodesFailed(
                nodes.map { Event.Provision.FailedNode(it.host.alias, it.step.wireName, it.reason, it.host.instanceId, it.instanceKept) },
            )
        }
}

/**
 * The control node failed (design D7): [step] did not succeed after its retries, or the control
 * plane stayed unhealthy past its deadline. Every pipeline is stopped and `up` fails at once.
 * [Event.Provision.ControlNodeFailed] has already reported it, so it is a [CommandFailedException]
 * and the command executor does not print it again.
 */
class ControlNodeFailedException(
    val alias: String,
    val step: String,
    val reason: String,
) : CommandFailedException("$alias failed at $step: $reason")
