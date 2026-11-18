package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstanceSetupFailedException
import com.rustyrazorblade.easydblab.services.NodeSetupContext
import com.rustyrazorblade.easydblab.services.NodeSetupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

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
    private val nodeOutput: NodeOutput by inject()

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--quiet", "-q"],
        description = ["Hide the output of each node's scripts; failures (with their last output) still print"],
    )
    var quiet = false

    override fun execute() {
        if (quiet) nodeOutput.quietly(::setupAll) else setupAll()
    }

    private fun setupAll() {
        val context = NodeSetupContext.from(clusterState)
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Stress, hosts.hostList, parallel = true) {
            reported(it.alias) { nodeSetupService.setupApp(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "", parallel = true) {
            reported(it.alias) { nodeSetupService.setupDatabase(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") {
            reported(it.alias) { nodeSetupService.setupControl(it.toHost()) }
        }
    }

    /**
     * Runs one host's [setup]; a failed setup script is reported as its own event, with the
     * host's output from this setup only, then fails the command.
     */
    private fun reported(
        alias: String,
        setup: () -> Unit,
    ) {
        nodeOutput.reset(alias)
        try {
            setup()
        } catch (e: InstanceSetupFailedException) {
            eventBus.emit(Event.Provision.InstanceSetupFailed(host = e.alias, reason = e.reason, outputTail = nodeOutput.tail(e.alias)))
            throw CommandFailedException(e.message ?: "Instance setup failed on ${e.alias}")
        }
    }
}
