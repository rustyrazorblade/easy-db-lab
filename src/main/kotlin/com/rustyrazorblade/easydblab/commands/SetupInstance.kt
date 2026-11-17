package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstanceSetupFailedException
import com.rustyrazorblade.easydblab.services.NodeSetupContext
import com.rustyrazorblade.easydblab.services.NodeSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Runs setup_instance.sh, and the per-type setup before it, on every node of the cluster. The
 * per-node work is [NodeSetupService]'s, which `up` also runs inside each node's own pipeline;
 * this command runs it for app nodes, then db nodes, then control, each group in parallel.
 */
@RequireProfileSetup
@Command(
    name = "setup-instances",
    aliases = ["si"],
    description = ["Runs setup_instance.sh on all Cassandra instances"],
)
class SetupInstance : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val nodeSetupService: NodeSetupService by inject()

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        val context = NodeSetupContext.from(clusterState)
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Stress, hosts.hostList, parallel = true) {
            reported { nodeSetupService.setupApp(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "", parallel = true) {
            reported { nodeSetupService.setupDatabase(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") {
            reported { nodeSetupService.setupControl(it.toHost()) }
        }
    }

    /** Runs one host's [setup]; a failed setup script is reported as its own event, then fails the command. */
    private fun reported(setup: () -> Unit) {
        try {
            setup()
        } catch (e: InstanceSetupFailedException) {
            eventBus.emit(Event.Provision.InstanceSetupFailed(host = e.alias, reason = e.reason))
            throw CommandFailedException(e.message ?: "Instance setup failed on ${e.alias}")
        }
    }
}
