package com.rustyrazorblade.easydblab.services.bringup

import java.util.concurrent.CompletableFuture

/**
 * The cloud provider's view of each node's instance, as every pipeline waits on it (design D9): when
 * it runs with a reachable address, when the provider's own health checks pass, and when it ends on
 * its own. It is the port a provider adapter implements; the EC2 one is
 * [com.rustyrazorblade.easydblab.services.bringup.aws.Ec2InstanceStateWatcher]. An adapter for an
 * existing cluster, whose machines `up` did not launch, completes `running` and `statusOk` at once.
 */
interface InstanceTracker {
    /** Starts watching [instanceId]; watching it again returns the same watch. */
    fun watch(instanceId: String): InstanceWatch

    /** Stops watching [instanceId]: the pipeline is done with it, or replaced it. */
    fun unwatch(instanceId: String)

    /** Starts tracking in the background until the returned handle is closed. */
    fun start(): AutoCloseable
}

/** The addresses a running instance was given. */
data class InstanceAddresses(
    val publicIp: String,
    val privateIp: String?,
)

/**
 * What one pipeline waits on for its instance: [running] once it runs with a public address,
 * [statusOk] once the provider's health checks pass, and [ended] with the provider's state name when
 * it ends on its own. An end fails the other two, so a pipeline waiting on either stops.
 */
class InstanceWatch(
    val instanceId: String,
) {
    val running = CompletableFuture<InstanceAddresses>()
    val statusOk = CompletableFuture<Unit>()
    val ended = CompletableFuture<String>()

    /** The instance runs at [addresses]. */
    fun markRunning(addresses: InstanceAddresses) {
        running.complete(addresses)
    }

    /** The provider's health checks passed. */
    fun markStatusOk() {
        statusOk.complete(Unit)
    }

    /** The instance ended, in the provider's [state]. [ended] completes first, so a wait failed by the end sees it. */
    fun end(state: String) {
        ended.complete(state)
        val failure = IllegalStateException("instance $instanceId ended: $state")
        running.completeExceptionally(failure)
        statusOk.completeExceptionally(failure)
    }

    /** Whether the instance runs and has not passed its health checks yet. */
    val awaitingStatus: Boolean
        get() = running.isDone && !running.isCompletedExceptionally && !statusOk.isDone
}
