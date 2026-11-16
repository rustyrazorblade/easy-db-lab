package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * control plane to be healthy, terminates the old instance and waits until it is gone, deletes the
 * node's K8s objects when it had registered, checks capacity for one instance, launches it under
 * the same alias in the same subnet, and records it in the state by alias.
 *
 * Replacements are serialised because two capacity checks run together could both see room for
 * one instance; they are rare, so the lock costs nothing.
 */
class NodeReplacer(
    private val fleet: InstanceFleet,
    private val cleanup: (alias: String) -> Unit,
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
    )

    /** How a replacement ended. */
    sealed interface Outcome {
        /** [host] is the new instance under the old alias; it is the node's [replacement]th. */
        data class Replaced(
            val host: ClusterHost,
            val replacement: Int,
        ) : Outcome

        /** No new instance runs for the node, for [reason]; its pipeline ends as failed. */
        data class NotReplaced(
            val reason: String,
        ) : Outcome
    }

    /**
     * Replaces [old], a [serverType] node, after [failure]. [registered] says whether its Node
     * object may exist, so its K8s objects are deleted. An [InterruptedException] propagates.
     */
    fun replace(
        serverType: ServerType,
        old: ClusterHost,
        registered: Boolean,
        failure: Failure,
    ): Outcome =
        when (val grant = budget.tryTake(old.alias)) {
            is ReplacementBudget.Grant.NodeLimitReached ->
                Outcome.NotReplaced("${failure.step.wireName} failed (${failure.reason}); the node had its ${grant.limit} replacements")
            is ReplacementBudget.Grant.ClusterLimitReached ->
                Outcome.NotReplaced("${failure.step.wireName} failed (${failure.reason}); the cluster made its ${grant.limit} replacements")
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
                lock.withLock { replaceNow(serverType, old, registered, grant.replacement) }
            }
        }

    private fun replaceNow(
        serverType: ServerType,
        old: ClusterHost,
        registered: Boolean,
        replacement: Int,
    ): Outcome {
        val ordinal = requireNotNull(serverType.ordinalOf(old.alias)) { "${old.alias} is not a ${serverType.serverType} alias" }
        health.awaitHealthy()
        return try {
            fleet.terminate(old.instanceId)
            if (registered) cleanup(old.alias)
            fleet.verifyReplacement(serverType, ordinal)
            val host = fleet.launch(serverType, ordinal)
            state.mergeHosts(serverType, listOf(host))
            eventBus.emit(Event.Provision.NodeReplaced(old.alias, old.instanceId, host.instanceId, host.privateIp))
            Outcome.Replaced(host, replacement)
        } catch (e: RuntimeException) {
            log.warn(e) { "Replacing ${old.alias} failed" }
            Outcome.NotReplaced("the replacement failed: ${e.message}")
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
