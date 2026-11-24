package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.DeclaresEnvironment
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import picocli.CommandLine.Command

/**
 * What a command asks of its execution environment, decided before it runs: whether the environment
 * provides the capabilities its [RequiresCapability] names, and whether its [RequireProfileSetup]
 * applies at all. A profile is an AWS profile, so it is required only where the target environment
 * has [NodeCapability.AWS_ACCOUNT].
 *
 * The target environment is the one the command declares it is about to record ([DeclaresEnvironment],
 * `init`), else the recorded one, else EC2 + K3s outside a workspace ([ClusterContractSource]). It is
 * read on every check, so an `init` in the REPL changes the answer for the next command.
 */
class EnvironmentRequirements(
    private val contracts: ClusterContractSource,
) {
    /**
     * The event reporting that [command], known to the user as [commandName] (its `@Command` name when
     * null), cannot run here, or null when the environment provides every capability it requires.
     */
    fun unavailable(
        command: PicoCommand,
        commandName: String? = null,
    ): Event.Environment.CommandUnavailable? {
        val required = command::class.annotations.filterIsInstance<RequiresCapability>().firstOrNull() ?: return null
        val contract = contracts.current()
        val missing = required.capabilities.filterNot { it in contract.nodeCapabilities }
        if (missing.isEmpty()) return null
        return Event.Environment.CommandUnavailable(
            command = commandName ?: nameOf(command),
            environment = contract.environment.displayName,
            missing = NodeCapability.entries.filter { it in missing },
            alternative = required.alternative.ifBlank { null },
        )
    }

    /** Whether [command] carries [RequireProfileSetup] and its target environment has an AWS account. */
    fun needsProfile(command: PicoCommand): Boolean =
        command::class.annotations.any { it is RequireProfileSetup } && NodeCapability.AWS_ACCOUNT in targetCapabilities(command)

    /**
     * Whether [command] carries [RequireSSHKey] and its environment reaches the nodes over SSH. The key
     * opens SSH sessions and the SOCKS tunnel; a local or brought cluster is reached through its
     * kubeconfig, so a client-driver command (`cassandra cql`, a kit's `sql`) needs no key there.
     */
    fun needsSshKey(command: PicoCommand): Boolean =
        command::class.annotations.any { it is RequireSSHKey } && NodeCapability.NODE_SSH in targetCapabilities(command)

    private fun targetCapabilities(command: PicoCommand): NodeCapabilities =
        (command as? DeclaresEnvironment)?.declaredEnvironment()?.declaredCapabilities
            ?: contracts.current().nodeCapabilities

    private companion object {
        /** The command's own `@Command` name, for a command run with no parsed path (a nested or scheduled one). */
        fun nameOf(command: PicoCommand): String =
            command::class.java.getAnnotation(Command::class.java)?.name ?: command::class.simpleName.orEmpty()
    }
}
