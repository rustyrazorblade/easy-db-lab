package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

/**
 * Download JVM and YAML config files.
 */
@RequireProfileSetup
@Command(
    name = "download-config",
    aliases = ["dc"],
    description = ["Download JVM and YAML config files"],
)
class DownloadConfig : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Option(names = ["--version"], description = ["Version to download, default is current"])
    var version = "current"

    companion object {
        val logger = KotlinLogging.logger {}
    }

    override fun execute() {
        val targeted = hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList)
        require(targeted.isNotEmpty()) {
            "No ${ServerType.Cassandra.serverType} host matches --hosts '${hosts.hostList}'"
        }

        // One download per distinct version among the targeted hosts, from the first host that runs
        // it: update-config looks for the directory of each host's own version, so a cluster that
        // runs a custom build on one node needs a directory for that build as well as the stock one.
        targeted
            .map { it.toHost() }
            .map { host -> host to remoteOps.getRemoteVersion(host, version) }
            .distinctBy { (_, resolved) -> resolved.localDir }
            .forEach { (host, resolved) -> download(host, resolved) }
    }

    private fun download(
        host: Host,
        resolvedVersion: Version,
    ) {
        logger.info {
            "Original version: $version.  Resolved version: ${resolvedVersion.versionString}. "
        }
        // Resolved against the workspace, where every other command looks for it, not the JVM's cwd.
        val localDir =
            context.workingDirectory
                .toPath()
                .resolve(resolvedVersion.localDir)
                .toFile()

        // Never overwrite: the directory may hold the operator's own edits. Say so, because
        // otherwise the command finishes silently having changed nothing.
        if (localDir.exists()) {
            eventBus.emit(Event.Cassandra.ConfigDownloadSkipped(resolvedVersion.localDir.toString()))
        } else {
            localDir.mkdirs()

            remoteOps.downloadDirectory(
                host,
                remoteDir = "${resolvedVersion.path}/conf",
                localDir = localDir,
                excludeFilters =
                    listOf(
                        "cassandra*.yaml",
                        "axonenv*",
                        "cassandra-rackdc.properties",
                    ),
            )
        }
    }
}
