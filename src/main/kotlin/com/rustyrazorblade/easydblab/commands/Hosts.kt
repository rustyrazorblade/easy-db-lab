package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Lists all hosts in the cluster.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "hosts",
    description = ["List all hosts in the cluster"],
)
class Hosts :
    PicoCommand,
    KoinComponent {
    private val context: Context by inject()
    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val clusterState by lazy { clusterStateManager.load() }

    @Option(names = ["-c"], description = ["Show Cassandra as a comma delimited list"])
    var cassandra: Boolean = false

    data class HostOutput(
        val cassandra: List<Host>,
        val stress: List<Host>,
        val control: List<Host>,
    )

    override fun execute() {
        if (!clusterStateManager.exists()) {
            eventBus.emit(
                Event.Message(
                    "Cluster state does not exist yet, most likely easy-db-lab up has not been run.",
                ),
            )
            return
        }

        val output =
            with(clusterState) {
                HostOutput(
                    getHosts(ServerType.Cassandra),
                    getHosts(ServerType.Stress),
                    getHosts(ServerType.Control),
                )
            }

        if (cassandra) {
            val hosts = clusterState.getHosts(ServerType.Cassandra)
            val csv = hosts.map { it.public }.joinToString(",")
            eventBus.emit(Event.Message(csv))
        } else {
            context.yaml.writeValue(System.out, output)
        }
    }
}
