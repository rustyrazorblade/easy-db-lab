package com.rustyrazorblade.easydblab.commands.cassandra

import com.fasterxml.jackson.annotation.JsonIgnore
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import kotlin.system.exitProcess

/**
 * Switch the active Cassandra version.
 */
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "use",
    description = ["Switch the active Cassandra version"],
)
class UseCassandra : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val commandExecutor: CommandExecutor by inject()

    @Parameters(description = ["Cassandra version"], index = "0")
    lateinit var version: String

    @Mixin
    var hosts = HostsMixin()

    @JsonIgnore val log = KotlinLogging.logger {}

    @Option(
        names = ["--java", "-j"],
        description = ["Java Version Override, 8, 11, 17, 21 or 25 accepted"],
    )
    var javaVersion = ""

    @Suppress("TooGenericExceptionCaught")
    override fun execute() {
        check(version.isNotBlank())
        val state = clusterState

        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Cassandra.ClusterStateNotFound)
            exitProcess(1)
        }

        val cassandraHosts = state.getHosts(ServerType.Cassandra)
        eventBus.emit(
            Event.Cassandra.UsingVersion(version, cassandraHosts.size, hosts.hostList),
        )

        eventBus.emit(
            Event.Cassandra.UseSideEffects(
                patchFile = Constants.ConfigPaths.CASSANDRA_PATCH_FILE,
                hosts = hostOperationsService.filteredHosts(state.hosts, ServerType.Cassandra, hosts.hostList).map { it.alias },
                javaVersion = javaVersion.ifBlank { null },
            ),
        )

        hostOperationsService.withHosts(state.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
            val it = host.toHost()
            if (javaVersion.isNotBlank()) {
                remoteOps.executeRemotely(it, "set-java-version $javaVersion $version")
            }
            remoteOps.executeRemotely(it, "sudo use-cassandra $version").text
            state.versions?.put(it.alias, version)
        }

        clusterStateManager.save(state)

        // Both steps are limited to the targeted hosts: each host's config directory is the one of the
        // version it now runs, and on a mixed-version cluster that is not the first host's version.
        // The executor reports a failed step as an exit code, never as an exception, so a step that
        // failed is checked here. The symlink switch above has already happened.
        val configured =
            commandExecutor.execute { DownloadConfig().apply { this.hosts = this@UseCassandra.hosts } } == 0 &&
                commandExecutor.execute { UpdateConfig().apply { this.hosts = this@UseCassandra.hosts } } == 0
        if (!configured) {
            throw CommandFailedException(
                "Version $version is active on the targeted hosts, but their configuration was not updated; see the error above.",
            )
        }

        eventBus.emit(Event.Cassandra.ConfigUpdateHint(version))
    }
}
