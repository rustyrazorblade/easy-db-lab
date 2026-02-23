package com.rustyrazorblade.easydblab.commands.tailscale

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.TailscaleService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Show Tailscale connection status on the control node.
 *
 * Displays the current Tailscale connection state, including:
 * - Connection status (connected/disconnected)
 * - Tailscale IP address
 * - Connected peers
 * - Advertised routes
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Show Tailscale connection status"],
)
class TailscaleStatus : PicoBaseCommand() {
    private val tailscaleService: TailscaleService by inject()

    override fun execute() {
        // Get control host
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            with(TermColors()) {
                eventBus.emit(
                    Event.Error(red("No control node found. Ensure your cluster is running with 'easy-db-lab up'.")),
                )
            }
            return
        }

        // Convert ClusterHost to Host for service calls
        val host = controlHost.toHost()

        // Check if Tailscale is connected
        val isConnected =
            tailscaleService.isConnected(host).getOrElse { false }

        if (!isConnected) {
            eventBus.emit(Event.Message("Tailscale is not running on ${controlHost.alias}."))
            eventBus.emit(Event.Message("Run 'easy-db-lab tailscale start' to connect."))
            return
        }

        // Get and display status
        tailscaleService
            .getStatus(host)
            .onSuccess { status ->
                eventBus.emit(Event.Message("Tailscale status on ${controlHost.alias}:"))
                eventBus.emit(Event.Message(status))
            }.onFailure { error ->
                with(TermColors()) {
                    eventBus.emit(
                        Event.Error(red("Failed to get Tailscale status: ${error.message}")),
                    )
                }
            }
    }
}
