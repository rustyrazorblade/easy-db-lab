package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command

/**
 * Lists available Cassandra versions installed on the cluster.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "list",
    aliases = ["ls"],
    description = ["List available versions"],
)
class ListVersions : PicoBaseCommand() {
    override fun execute() {
        clusterState.getHosts(ServerType.Cassandra).first().let {
            val response = remoteOps.executeRemotely(it, "ls /usr/local/cassandra", output = false)
            response.text.split("\n").filter { line -> line != "current" }.forEach { line ->
                eventBus.emit(Event.Message(line))
            }
        }
    }
}
