package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command

/**
 * Lists the Cassandra versions installed on the cluster, plus any lazily-declared version that is
 * declared but not yet installed anywhere.
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
            val installed =
                response.text
                    .split("\n")
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() && line != "current" }
            eventBus.emit(buildVersionList(installed, declaredCassandraVersions(context)))
        }
    }

    /**
     * A lazily-declared version is not baked into the AMI, so it only shows up here — as
     * installable — until someone actually installs it.
     */
    internal fun buildVersionList(
        installed: List<String>,
        declared: List<CassandraVersion>,
    ): Event.Cassandra.VersionList =
        Event.Cassandra.VersionList(
            installed,
            declared.filter { it.lazy && it.version !in installed }.map { it.version },
        )
}
