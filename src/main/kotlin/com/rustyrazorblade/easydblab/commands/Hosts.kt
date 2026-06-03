package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
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

    @Option(names = ["-c"], description = ["Show db nodes as a comma delimited list (deprecated: use --db)"])
    var cassandra: Boolean = false

    @Option(names = ["--db"], description = ["Show db nodes as a comma delimited list"])
    var db: Boolean = false

    @Option(names = ["--app"], description = ["Show app nodes as a comma delimited list"])
    var app: Boolean = false

    @Option(names = ["--private"], description = ["Use private IPs (default: public)"])
    var usePrivateIp: Boolean = false

    data class HostOutput(
        val db: List<Host>,
        val app: List<Host>,
        val control: List<Host>,
    )

    override fun execute() {
        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Command.HostsNoClusterState)
            return
        }

        val showDbCsv = cassandra || db
        val showAppCsv = app

        if (showDbCsv) {
            val hosts = clusterState.getHosts(ServerType.Cassandra)
            val csv = hosts.map { if (usePrivateIp) it.private else it.public }.joinToString(",")
            println(csv)
        } else if (showAppCsv) {
            val hosts = clusterState.getHosts(ServerType.Stress)
            val csv = hosts.map { if (usePrivateIp) it.private else it.public }.joinToString(",")
            println(csv)
        } else {
            val output =
                with(clusterState) {
                    HostOutput(
                        db = getHosts(ServerType.Cassandra),
                        app = getHosts(ServerType.Stress),
                        control = getHosts(ServerType.Control),
                    )
                }
            context.yaml.writeValue(System.out, output)
        }
    }
}
