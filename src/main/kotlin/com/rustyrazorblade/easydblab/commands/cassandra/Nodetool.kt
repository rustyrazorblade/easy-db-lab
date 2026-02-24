package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
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
@McpCommand
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
            val result = remoteOps.executeRemotely(host.toHost(), "/usr/local/cassandra/current/bin/nodetool $command")
            eventBus.emit(Event.Cassandra.NodetoolOutput(host.alias, result.text))
        }
    }
}
