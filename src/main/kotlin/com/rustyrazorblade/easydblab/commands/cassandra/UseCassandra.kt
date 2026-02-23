package com.rustyrazorblade.easydblab.commands.cassandra

import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
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
 * Use a Cassandra version (3.0, 3.11, 4.0, 4.1).
 */
@McpCommand
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "use",
    description = ["Use a Cassandra version (3.0, 3.11, 4.0, 4.1)"],
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
        description = ["Java Version Override, 8, 11 or 17 accepted"],
    )
    var javaVersion = ""

    @Suppress("TooGenericExceptionCaught")
    override fun execute() {
        check(version.isNotBlank())
        val state = clusterState

        if (!clusterStateManager.exists()) {
            eventBus.emit(
                Event.Error(
                    "cluster state not found. Please run easy-db-lab up first to " +
                        "establish IP addresses for seed listing.",
                ),
            )
            exitProcess(1)
        }

        val cassandraHosts = state.getHosts(ServerType.Cassandra)
        eventBus.emit(
            Event.Message("Using version $version on ${cassandraHosts.size} hosts, filter: $hosts"),
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

        commandExecutor.execute { DownloadConfig() }

        // make sure we only apply to the filtered hosts
        commandExecutor.execute {
            UpdateConfig().apply { this.hosts = this@UseCassandra.hosts }
        }

        with(TermColors()) {
            eventBus.emit(
                Event.Message(
                    "You can update ${green("cassandra.patch.yaml")} and the JVM config files under ${green(version)}, " +
                        "then run ${green("easy-db-lab update-config")} to apply the changes.",
                ),
            )
        }
    }
}
