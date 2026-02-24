package com.rustyrazorblade.easydblab.commands.tailscale

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
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
            eventBus.emit(Event.Tailscale.NoControlNode)
            return
        }

        // Convert ClusterHost to Host for service calls
        val host = controlHost.toHost()

        // Check if Tailscale is running
        val isConnected =
            tailscaleService.isConnected(host).getOrElse { false }
        if (!isConnected) {
            eventBus.emit(Event.Tailscale.NotRunning(controlHost.alias))
            return
        }

        // Stop Tailscale
        tailscaleService
            .stopTailscale(host)
            .onSuccess {
                deleteTailscaleAuthKey()
                eventBus.emit(Event.Tailscale.StoppedSuccessfully)
            }.onFailure { error ->
                eventBus.emit(Event.Tailscale.StopFailed(error.message ?: "unknown error"))
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
            eventBus.emit(Event.Tailscale.AuthKeyDeleted(keyId))
        } catch (e: Exception) {
            log.warn(e) { "Failed to delete Tailscale auth key: $keyId" }
        }

        clusterState.updateTailscaleAuthKeyId(null)
        clusterStateManager.save(clusterState)
    }
}
