package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.reason
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.cassandraversions.Cached
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.ClusterListing
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionSources
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionTable
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Lists every Cassandra version that can be installed, with its source, and, in a cluster
 * workspace, the db nodes that have each one (design D9). Runs with no cluster.
 *
 * A read-only display command, so it prints the table. Every node is asked; a node that cannot be
 * listed is named with its error and the command exits non-zero, so the table never presents part of
 * the cluster as the whole.
 */
@RequireProfileSetup
@Command(
    name = "list",
    aliases = ["ls"],
    description = ["List the Cassandra versions you can install, and the ones on each node"],
)
class ListVersions : PicoBaseCommand() {
    private val sources: VersionSources by inject()
    private val installer: CassandraInstaller by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Option(names = ["--refresh"], description = ["Fetch the ASF and cassandra-builds lists again"])
    var refresh: Boolean = false

    override fun execute() {
        val asf = sources.asfReleases(refresh)
        val builds = sources.cassandraBuilds(refresh)
        val cluster = if (clusterStateManager.exists()) listCluster() else null

        val rows = VersionTable.rows(sources.profileBuilds(), asf.value, builds.value, sources.catalogBuildNames(), cluster)
        val notes =
            listOfNotNull(staleNote("ASF", asf), staleNote("cassandra-builds", builds)) +
                cluster?.unreachable?.map { (alias, error) -> "$alias: could not list versions: $error" }.orEmpty()
        println((listOf(VersionTable.render(rows)) + notes).joinToString("\n"))

        cluster?.unreachable?.keys?.takeIf { it.isNotEmpty() }?.let {
            throw CommandFailedException("Could not list the versions on ${it.joinToString(", ")}")
        }
    }

    private fun listCluster(): ClusterListing? {
        val state = clusterState
        val hosts = state.hosts[ServerType.Cassandra].orEmpty()
        if (hosts.isEmpty()) return null
        val results = hostOperationsService.collectFromHosts(hosts, parallel = true) { installer.installedOn(it) }
        return ClusterListing(
            perNode = results.mapNotNull { r -> r.result.getOrNull()?.let { r.host.alias to it } }.toMap(),
            unreachable =
                results
                    .mapNotNull { r ->
                        r.result.exceptionOrNull()?.let { r.host.alias to redactUrlCredentials(it.reason()) }
                    }.toMap(),
            recordedSources =
                state.nodes.values
                    .flatMap { it.installed.values }
                    .associate { it.version to it.source },
        )
    }

    private fun staleNote(
        source: String,
        cached: Cached<*>,
    ): String? = "The $source list could not be fetched; showing the cached copy from ${cached.fetchedAt} (stale).".takeIf { cached.stale }
}
