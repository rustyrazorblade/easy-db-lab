package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Get IP address for a host by alias.
 *
 * This command looks up a host by its alias (e.g., db0, stress0) and
 * returns either its public or private IP address. Useful for scripts that
 * need to pass IP addresses to other commands.
 *
 * Examples:
 *   easy-db-lab ip db0           # Returns public IP (default)
 *   easy-db-lab ip db0 --public  # Returns public IP
 *   easy-db-lab ip db0 --private # Returns private IP
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "ip",
    description = ["Get IP address for a host by alias"],
)
class Ip :
    PicoCommand,
    KoinComponent {
    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val clusterState by lazy { clusterStateManager.load() }

    @Parameters(
        index = "0",
        description = ["Host alias (e.g., db0, stress0)"],
    )
    lateinit var host: String

    @Option(names = ["--public"], description = ["Return public IP (default)"])
    var publicIp: Boolean = false

    @Option(names = ["--private"], description = ["Return private IP"])
    var privateIp: Boolean = false

    override fun execute() {
        // Find the host across all server types
        val foundHost =
            ServerType.entries
                .flatMap { clusterState.getHosts(it) }
                .firstOrNull { it.alias == host }
                ?: error("Host not found: $host")

        // Default to public if neither flag specified
        val ip =
            when {
                privateIp -> foundHost.private
                else -> foundHost.public
            }

        eventBus.emit(Event.Message(ip))
    }
}
