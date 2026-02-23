package com.rustyrazorblade.easydblab.commands.cassandra

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.SidecarService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.io.File

/**
 * Start cassandra on all nodes via service command.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "start",
    description = ["Start cassandra on all nodes via service command"],
)
class Start : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val cassandraService: CassandraService by inject()
    private val sidecarService: SidecarService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    companion object {
        private const val DEFAULT_SLEEP_BETWEEN_STARTS_SECONDS = 120L
    }

    @Option(names = ["--sleep"], description = ["Time to sleep between starts in seconds"])
    var sleep: Long = DEFAULT_SLEEP_BETWEEN_STARTS_SECONDS

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        with(TermColors()) {
            hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
                val h = host.toHost()
                eventBus.emit(Event.Cassandra.Starting(h.alias))
                // start() defaults to wait=true, which includes waiting for UP/NORMAL
                cassandraService.start(h).getOrThrow()
            }

            // Start cassandra-sidecar on Cassandra nodes
            hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
                sidecarService
                    .start(host.toHost())
                    .onFailure { e ->
                        eventBus.emit(Event.Error("Warning: Failed to start cassandra-sidecar on ${host.alias}: ${e.message}"))
                    }
            }
        }

        // Start axon-agent on Cassandra nodes if configured
        if (userConfig.axonOpsOrg.isNotBlank() && userConfig.axonOpsKey.isNotBlank()) {
            eventBus.emit(Event.Message("Starting axon-agent on Cassandra nodes..."))
            hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, "", parallel = true) { host ->
                remoteOps.executeRemotely(host.toHost(), "sudo systemctl start axon-agent")
            }
        }

        // Inform user about AxonOps Workbench configuration if it exists
        val axonOpsWorkbenchFile = File("axonops-workbench.json")
        if (axonOpsWorkbenchFile.exists()) {
            eventBus.emit(
                Event.Message(
                    """
                    |
                    |AxonOps Workbench configuration available:
                    |To import into AxonOps Workbench, run:
                    |  /path/to/axonops-workbench -v --import-workspace=axonops-workbench.json
                    |
                    """.trimMargin(),
                ),
            )
        }
    }
}
