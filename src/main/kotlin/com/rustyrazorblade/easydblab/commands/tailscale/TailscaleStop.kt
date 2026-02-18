package com.rustyrazorblade.easydblab.commands.tailscale

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.services.TailscaleService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Stop Tailscale VPN on the control node.
 *
 * This command disconnects from the Tailscale network and stops the daemon.
 * The node will no longer be accessible via Tailscale until `tailscale start` is run again.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Stop Tailscale VPN on the control node"],
)
class TailscaleStop : PicoBaseCommand() {
    private val tailscaleService: TailscaleService by inject()
    private val user: User by inject()
    private val log = KotlinLogging.logger {}

    override fun execute() {
        // Get control host
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            with(TermColors()) {
                outputHandler.handleMessage(
                    red("No control node found. Ensure your cluster is running with 'easy-db-lab up'."),
                )
            }
            return
        }

        // Convert ClusterHost to Host for service calls
        val host = controlHost.toHost()

        // Check if Tailscale is running
        val isConnected =
            tailscaleService.isConnected(host).getOrElse { false }
        if (!isConnected) {
            outputHandler.handleMessage("Tailscale is not running on ${controlHost.alias}.")
            return
        }

        // Stop Tailscale
        tailscaleService
            .stopTailscale(host)
            .onSuccess {
                deleteTailscaleAuthKey()
                with(TermColors()) {
                    outputHandler.handleMessage(green("Tailscale stopped successfully."))
                }
            }.onFailure { error ->
                with(TermColors()) {
                    outputHandler.handleMessage(
                        red("Failed to stop Tailscale: ${error.message}"),
                    )
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteTailscaleAuthKey() {
        val keyId = clusterState.tailscaleAuthKeyId ?: return

        val clientId = user.tailscaleClientId
        val clientSecret = user.tailscaleClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn { "Tailscale OAuth credentials not configured, cannot delete auth key $keyId" }
            return
        }

        try {
            tailscaleService.deleteAuthKey(clientId, clientSecret, keyId)
            outputHandler.handleMessage("Deleted Tailscale auth key: $keyId")
        } catch (e: Exception) {
            log.warn(e) { "Failed to delete Tailscale auth key: $keyId" }
        }

        clusterState.updateTailscaleAuthKeyId(null)
        clusterStateManager.save(clusterState)
    }
}
