package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
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
            reported(it) { nodeSetupService.setupApp(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "", parallel = true) {
            reported(it) { nodeSetupService.setupDatabase(it.toHost(), context) }
        }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") {
            reported(it) { nodeSetupService.setupControl(it.toHost(), context) }
        }
    }

    /**
     * Runs one host's [setup]; a failed setup script, or any other failed remote command of the
     * setup, is reported as its own event, with the host's output from this setup only, then fails
     * the command. Another command's reason is its one-line summary: its output is in the tail.
     */
    private fun reported(
        host: ClusterHost,
        setup: () -> Unit,
    ) {
        nodeOutput.reset(host.alias, host.instanceId)
        try {
            setup()
        } catch (e: InstanceSetupFailedException) {
            fail(e.alias, e.reason)
        } catch (e: RemoteCommandFailedException) {
            fail(host.alias, e.summary)
        }
    }

    private fun fail(
        alias: String,
        reason: String,
    ): Nothing {
        eventBus.emit(Event.Provision.InstanceSetupFailed(host = alias, reason = reason, outputTail = nodeOutput.tail(alias)))
        throw CommandFailedException("Instance setup failed on $alias: $reason")
    }
}
