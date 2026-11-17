package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.locks.ReentrantLock

/**
 * The cloud provider side of replacing a node, as [NodeReplacer] needs it: a port, so the
 * replacement logic holds no provider type. The EC2 adapter is
 * [com.rustyrazorblade.easydblab.services.bringup.aws.Ec2InstanceFleet]; the tests use a fake that
 * records the order of the calls.
 */
interface InstanceFleet {
    /** Terminates [instanceId] and returns once the provider no longer reports it starting or running. */
    fun terminate(instanceId: String)

    /** Fails when the account cannot hold, or the provider would refuse, one more [serverType] instance at [ordinal]. */
    fun verifyReplacement(
        serverType: ServerType,
        ordinal: Int,
    )

    /**
     * Launches the one instance for [serverType] at [ordinal], in the ordinal's zone, names it,
     * and returns it as the host it becomes under the ordinal's alias.
     */
    fun launch(
        serverType: ServerType,
        ordinal: Int,
    ): ClusterHost
}

/**
 * Replaces a node whose step failed for good or whose instance ended (design D6). It takes a
 * replacement from the [budget], then, one replacement at a time under its lock: waits for the
 * control plane to be healthy, terminates the old instance and waits until it is gone, checks
 * capacity for one instance, launches it under the same alias in the same subnet, and records it
 * in the state by alias. The alias's K8s objects are removed by the replacement's own pipeline,
 * just before its first join, once the control plane is open.
 *
 * Replacements are serialised because two capacity checks run together could both see room for
 * one instance; they are rare, so the lock costs nothing.
 */
class NodeReplacer(
    private val fleet: InstanceFleet,
    private val state: ClusterStateWriter,
    private val budget: ReplacementBudget,
    private val health: HealthLatch,
    private val eventBus: EventBus,
) {
    private val lock = ReentrantLock()

    /** Why a node needs a new instance: the step that failed, and how. */
    data class Failure(
        val step: PipelineStep,
        val reason: String,
    ) {
        /** The step and its reason, as a failed node reports them. */
        fun describe(): String = "${step.wireName} failed ($reason)"
    }

    /** How a replacement ended. */
    sealed interface Outcome {
        /** [host] is the new instance under the old alias; it is the node's [replacement]th. */
        data class Replaced(
            val host: ClusterHost,
            val replacement: Int,
        ) : Outcome

        /**
         * No new instance runs for the node, for [reason]; its pipeline ends as failed.
         * [oldInstanceKept] is false when the old instance was terminated before a capacity check
         * or launch failed, so the node has no instance left.
         */
        data class NotReplaced(
            val reason: String,
            val oldInstanceKept: Boolean,
        ) : Outcome
    }

    /**
     * Replaces [old], a [serverType] node, after [failure]. An [InterruptedException] propagates:
     * an interrupt while waiting for the lock, for a healthy control plane, or before the old
     * instance is terminated stops the replacement with nothing terminated.
     */
    fun replace(
        serverType: ServerType,
        old: ClusterHost,
        failure: Failure,
    ): Outcome =
        when (val grant = budget.tryTake(old.alias)) {
            is ReplacementBudget.Grant.NodeLimitReached ->
                Outcome.NotReplaced("${failure.describe()}; the node had its ${grant.limit} replacements", oldInstanceKept = true)
            is ReplacementBudget.Grant.ClusterLimitReached ->
                Outcome.NotReplaced("${failure.describe()}; the cluster made its ${grant.limit} replacements", oldInstanceKept = true)
            is ReplacementBudget.Grant.Granted -> {
                eventBus.emit(
                    Event.Provision.NodeReplacing(
                        old.alias,
                        old.instanceId,
                        failure.step.wireName,
                        failure.reason,
                        grant.replacement,
                        grant.limit,
                    ),
                )
                lock.lockInterruptibly()
                try {
                    replaceNow(serverType, old, failure, grant.replacement)
                } finally {
                    lock.unlock()
                }
            }
        }

    private fun replaceNow(
        serverType: ServerType,
        old: ClusterHost,
        failure: Failure,
        replacement: Int,
    ): Outcome {
        val ordinal = requireNotNull(serverType.ordinalOf(old.alias)) { "${old.alias} is not a ${serverType.serverType} alias" }
        health.awaitHealthy()
        if (Thread.interrupted()) throw InterruptedException("replacing ${old.alias} was interrupted before its instance was terminated")
        var phase = Phase.TERMINATE
        return try {
            fleet.terminate(old.instanceId)
            phase = Phase.VERIFY
            fleet.verifyReplacement(serverType, ordinal)
            phase = Phase.LAUNCH
            val host = fleet.launch(serverType, ordinal)
            state.mergeHosts(serverType, listOf(host))
            eventBus.emit(Event.Provision.NodeReplaced(old.alias, old.instanceId, host.instanceId, host.privateIp))
            Outcome.Replaced(host, replacement)
        } catch (e: RuntimeException) {
            // An SDK call cut short by an interrupt surfaces as a RuntimeException; the interrupt wins.
            if (Thread.interrupted()) throw InterruptedException("replacing ${old.alias} was interrupted at ${phase.wireName}")
            log.warn(e) { "Replacing ${old.alias} failed at ${phase.wireName}" }
            val kept = phase == Phase.TERMINATE
            eventBus.emit(Event.Provision.NodeReplaceFailed(old.alias, old.instanceId, phase.wireName, e.messageOrString(), kept))
            Outcome.NotReplaced(
                "${failure.describe()}; the replacement failed at ${phase.wireName}: ${e.messageOrString()}",
                oldInstanceKept = kept,
            )
        }
    }

    /** The replacement call that was running when a replacement failed. */
    private enum class Phase(
        val wireName: String,
    ) {
        TERMINATE("terminate"),
        VERIFY("capacity-check"),
        LAUNCH("launch"),
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
