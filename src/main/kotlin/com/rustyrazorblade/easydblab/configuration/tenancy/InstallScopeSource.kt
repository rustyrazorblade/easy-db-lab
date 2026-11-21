package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract

/**
 * The [InstallScope] of the workspace a command runs in, resolved on each call. A service that
 * applies or reads Kubernetes objects takes this instead of a namespace constant, so the namespace
 * always comes from the cluster contract and a test can bind a fixed scope.
 */
fun interface InstallScopeSource {
    /** The scope the current state resolves to. */
    fun current(): InstallScope
}

/**
 * The production [InstallScopeSource]: the workspace's `state.json`, read on every call so an
 * `init --namespace` inside the REPL or the server takes effect on the next command.
 */
class StateInstallScopeSource(
    private val clusterStateManager: ClusterStateManager,
) : InstallScopeSource {
    override fun current(): InstallScope {
        val state = clusterStateManager.load()
        return InstallScope.of(state, ClusterContract.of(state))
    }
}
