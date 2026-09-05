package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Lists the Cassandra versions installed on the cluster, plus anything installable that is not on
 * it yet: lazily-declared versions, and builds published to the profile's S3 bucket.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "list",
    aliases = ["ls"],
    description = ["List available versions"],
)
class ListVersions : PicoBaseCommand() {
    private val catalog: CassandraBuildCatalog by inject()

    override fun execute() {
        clusterState.getHosts(ServerType.Cassandra).first().let {
            val response = remoteOps.executeRemotely(it, "ls /usr/local/cassandra", output = false)
            val installed =
                response.text
                    .split("\n")
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() && line != "current" }
            eventBus.emit(
                buildVersionList(
                    installed,
                    declaredCassandraVersions(context),
                    catalog.list().map { build -> build.name },
                ),
            )
        }
    }

    /**
     * A lazily-declared version is not baked into the AMI, and a published build was never in one
     * at all, so both only show up here — as installable — until someone actually installs them.
     */
    internal fun buildVersionList(
        installed: List<String>,
        declared: List<CassandraVersion>,
        builds: List<String> = emptyList(),
    ): Event.Cassandra.VersionList =
        Event.Cassandra.VersionList(
            installed,
            declared.filter { it.lazy && it.version !in installed }.map { it.version },
            builds.filter { it !in installed },
        )
}
