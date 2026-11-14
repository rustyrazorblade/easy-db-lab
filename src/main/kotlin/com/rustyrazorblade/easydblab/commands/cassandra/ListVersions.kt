package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Lists the Cassandra versions installed on the cluster, plus anything installable that is not on
 * it yet: lazily-declared versions, and builds published to the profile's S3 bucket.
 *
 * Every node is asked. A version that only some nodes have is listed with the nodes that have it,
 * and a node that cannot be listed is named, so the output never presents one node's state as the
 * whole cluster's.
 */
@RequireProfileSetup
@Command(
    name = "list",
    aliases = ["ls"],
    description = ["List available versions on every node"],
)
class ListVersions : PicoBaseCommand() {
    private val catalog: CassandraBuildCatalog by inject()
    private val hostOperationsService: HostOperationsService by inject()

    override fun execute() {
        val hosts = clusterState.hosts[ServerType.Cassandra].orEmpty()
        check(hosts.isNotEmpty()) { "No ${ServerType.Cassandra.serverType} nodes in this cluster." }

        val results =
            hostOperationsService.collectFromHosts(hosts, parallel = true) { host ->
                remoteOps
                    .executeRemotely(host.toHost(), "ls /usr/local/cassandra", output = false)
                    .text
                    .lines()
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() && line != "current" }
            }
        val perHost = results.mapNotNull { r -> r.result.getOrNull()?.let { r.host.alias to it } }.toMap()
        val unreachable =
            results
                .mapNotNull { r -> r.result.exceptionOrNull()?.let { r.host.alias to (it.message ?: it.javaClass.simpleName) } }
                .toMap()

        eventBus.emit(
            buildVersionList(
                perHost,
                unreachable,
                declaredCassandraVersions(context),
                catalog.list().map { build -> build.name },
                hosts.map { it.alias },
            ),
        )
        if (unreachable.isNotEmpty()) {
            throw CommandFailedException("Could not list the versions on ${unreachable.keys.joinToString(", ")}")
        }
    }

    /**
     * Combines each node's installed versions. A version every listed node has is plain; one only
     * some nodes have is reported with the nodes that have it, so a mixed cluster never reads as
     * uniform. A lazily-declared version is not baked into the AMI, and a published build was never
     * in one at all, so both only show up here, as installable, until a node actually has them.
     */
    internal fun buildVersionList(
        perHost: Map<String, List<String>>,
        unreachable: Map<String, String>,
        declared: List<CassandraVersion>,
        builds: List<String> = emptyList(),
        nodes: List<String> = perHost.keys.toList() + unreachable.keys,
    ): Event.Cassandra.VersionList {
        val everywhere =
            perHost.values
                .firstOrNull()
                .orEmpty()
                .filter { version -> perHost.values.all { version in it } }
        val anywhere = perHost.values.flatten().distinct()
        val partial =
            (anywhere - everywhere.toSet())
                .sorted()
                .associateWith { version -> perHost.filterValues { version in it }.keys.toList() }
        return Event.Cassandra.VersionList(
            everywhere,
            declared.filter { it.lazy && it.version !in anywhere }.map { it.version },
            builds.filter { it !in anywhere },
            partial,
            unreachable,
            nodes,
        )
    }

    /** The single-listing form: [installed] is the whole cluster's answer. */
    internal fun buildVersionList(
        installed: List<String>,
        declared: List<CassandraVersion>,
        builds: List<String> = emptyList(),
    ): Event.Cassandra.VersionList = buildVersionList(mapOf("node" to installed), emptyMap(), declared, builds)
}
