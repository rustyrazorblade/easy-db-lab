package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException

/**
 * The check `up` and `down` run first: easy-db-lab provisions and tears down only the environments
 * whose contract says it has a provisioner ([ClusterContract.hasProvisioner]), so in any other recorded
 * environment the command names it and fails before it loads the profile, writes state or calls AWS.
 * A later provisioner (K3s in Docker, #1041) changes the contract, not this guard.
 */
object ProvisionerGuard {
    /**
     * Fails [command] with [Event.Environment.ProvisionerUnavailable] when [state] records an
     * environment with no provisioner; a workspace with no state is left to the command.
     */
    fun require(
        command: String,
        state: ClusterState?,
        eventBus: EventBus,
    ) {
        val contract = state?.let(ClusterContract::of) ?: return
        if (contract.hasProvisioner) return
        val environment = contract.environment.displayName
        eventBus.emit(Event.Environment.ProvisionerUnavailable(command, environment))
        throw CommandFailedException("'$command' has no provisioner for $environment")
    }
}
