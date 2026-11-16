package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException

/**
 * The check `up` and `down` run first: easy-db-lab provisions and tears down only EC2 plus K3s
 * today, so in any other recorded environment the command names it and fails before it loads the
 * profile, writes state or calls AWS. A later provisioner (K3s in Docker) widens [hasProvisioner].
 */
object ProvisionerGuard {
    /** Whether easy-db-lab can provision and tear down [environment]. */
    fun hasProvisioner(environment: Environment): Boolean =
        when (environment) {
            Environment.Ec2K3s -> true
            is Environment.K3sDocker, is Environment.Kubeconfig -> false
        }

    /**
     * Fails [command] with [Event.Environment.ProvisionerUnavailable] when [state] records an
     * environment with no provisioner; a workspace with no state is left to the command.
     */
    fun require(
        command: String,
        state: ClusterState?,
        eventBus: EventBus,
    ) {
        val environment = state?.environment ?: return
        if (hasProvisioner(environment)) return
        eventBus.emit(Event.Environment.ProvisionerUnavailable(command, environment.displayName))
        throw CommandFailedException("'$command' has no provisioner for ${environment.displayName}")
    }
}
