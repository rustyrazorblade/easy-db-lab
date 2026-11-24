package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Parameters

/**
 * Execute nodetool commands on Cassandra nodes.
 *
 * Examples:
 *   easy-db-lab cassandra nt status
 *   easy-db-lab cassandra nt --hosts db0,db2 info
 *   easy-db-lab cassandra nt ring
 */
@RequiresCapability(NodeCapability.NODE_SSH, alternative = Constants.Cassandra.KIT_ALTERNATIVE)
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "nt",
    description = ["Execute nodetool on Cassandra nodes"],
)
class Nodetool : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Parameters(description = ["Nodetool command and arguments"])
    var args: List<String> = emptyList()

    override fun execute() {
        if (args.isEmpty()) {
            eventBus.emit(Event.Cassandra.NodetoolUsage)
            return
        }

        val command = args.joinToString(" ")

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            // Each line prints as it arrives, tagged with the host, so a failure is named by its
            // one-line summary: its output was already printed and is not repeated.
            try {
                remoteOps.executeRemotely(host.toHost(), "/usr/local/cassandra/current/bin/nodetool $command")
            } catch (e: RemoteCommandFailedException) {
                throw CommandFailedException("nodetool failed on ${host.alias}: ${e.summary}")
            }
        }
    }
}
