package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager

/**
 * The cluster contract of the workspace a command runs in, resolved on each call. Consumers take this
 * instead of resolving the contract themselves, so a test can bind a fixed one.
 */
fun interface ClusterContractSource {
    /** The contract the current state resolves to. */
    fun current(): ClusterContract
}

/**
 * The production [ClusterContractSource]: the workspace's `state.json`, read on every call so an
 * `init` inside the REPL or the server takes effect on the next command. With no state, the target
 * is a default [Environment.Ec2K3s] cluster, which is what a command outside a workspace (such as
 * `build-image`) works against.
 */
class StateClusterContractSource(
    private val clusterStateManager: ClusterStateManager,
) : ClusterContractSource {
    override fun current(): ClusterContract = ClusterContract.of(currentState())

    private fun currentState(): ClusterState =
        when (clusterStateManager.exists()) {
            true -> clusterStateManager.load()
            false -> ClusterState(name = NO_STATE_NAME)
        }

    private companion object {
        /** The name of the default state that stands in for a workspace with no `state.json`. */
        const val NO_STATE_NAME = "cluster"
    }
}
