package com.rustyrazorblade.easydblab.commands.tailscale

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import com.rustyrazorblade.easydblab.configuration.update
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.services.TailscaleApiException
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
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Stop Tailscale VPN on the control node"],
)
class TailscaleStop : PicoBaseCommand() {
    private val tailscaleService: TailscaleService by inject()
    private val profile: ProfileAccess by inject()
    private val log = KotlinLogging.logger {}

    /**
     * Non-zero when the daemon did not stop or the control node's device could not be removed,
     * after an error event said why.
     */
    private var exitCode = 0

    override fun call(): Int {
        val lifecycleExit = super.call()
        return if (lifecycleExit != 0) lifecycleExit else exitCode
    }

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
            // A daemon already down still leaves its device in the tailnet.
            removeTailscaleDevice()
            return
        }

        // Stop Tailscale
        tailscaleService
            .stopTailscale(host)
            .onSuccess {
                deleteTailscaleAuthKey()
                removeTailscaleDevice()
                eventBus.emit(Event.Tailscale.StoppedSuccessfully)
            }.onFailure { error ->
                eventBus.emit(Event.Tailscale.StopFailed(error.message ?: "unknown error"))
                exitCode = Constants.ExitCodes.ERROR
            }
    }

    /**
     * Deletes the auth key `tailscale start` recorded. Missing OAuth credentials, or a failed delete,
     * emit [Event.Tailscale.AuthKeyNotDeleted] and the exit code stays zero: the stop itself worked, and
     * the key stays valid in the tailnet until it expires or is revoked in the Tailscale admin console.
     * A failed delete still clears the recorded key ID, so a later `stop` does not retry it; missing
     * credentials return before that, so the ID stays recorded.
     */
    private fun deleteTailscaleAuthKey() {
        val keyId = clusterState.tailscaleAuthKeyId ?: return

        val saved = profile.current().tailscale
        val clientId = saved?.clientId.orEmpty()
        val clientSecret = saved?.clientSecret.orEmpty()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            eventBus.emit(Event.Tailscale.AuthKeyNotDeleted(keyId, "no Tailscale OAuth credentials are configured"))
            return
        }

        try {
            tailscaleService.deleteAuthKey(clientId, clientSecret, keyId)
            eventBus.emit(Event.Tailscale.AuthKeyDeleted(keyId))
        } catch (e: TailscaleApiException) {
            log.warn(e) { "Failed to delete Tailscale auth key: $keyId" }
            eventBus.emit(Event.Tailscale.AuthKeyNotDeleted(keyId, e.messageOrString()))
        }

        clusterStateManager.update(clusterState) { it.updateTailscaleAuthKeyId(null) }
    }

    /**
     * Removes the control node's device, by the ID `tailscale start` recorded, the same way `down`
     * does. Stopping the daemon leaves the device in the tailnet, and the next `tailscale start`
     * registers a new one beside it; a daemon found already down leaves it there too, so this runs
     * on that path as well. A device already gone counts as removed; any other failure
     * (a missing `devices:core` scope, no OAuth credentials) is reported, makes the command exit
     * non-zero, and leaves the ID recorded so a later `stop` or `down` retries.
     */
    private fun removeTailscaleDevice() {
        val deviceId = clusterState.tailscaleDeviceId
        if (deviceId.isNullOrBlank()) return

        val saved = profile.current().tailscale
        val clientId = saved?.clientId.orEmpty()
        val clientSecret = saved?.clientSecret.orEmpty()

        val failure =
            if (clientId.isBlank() || clientSecret.isBlank()) {
                "no Tailscale OAuth credentials are configured. Configure them with 'easy-db-lab profile setup' " +
                    "and run 'easy-db-lab tailscale stop' again, or remove the device at " +
                    "https://login.tailscale.com/admin/machines."
            } else {
                try {
                    tailscaleService.deleteDevice(clientId, clientSecret, deviceId)
                    null
                } catch (e: TailscaleApiException) {
                    e.message ?: e::class.simpleName.orEmpty()
                }
            }

        if (failure != null) {
            eventBus.emit(Event.Tailscale.DeviceNotRemoved(deviceId, failure))
            exitCode = Constants.ExitCodes.ERROR
            return
        }
        eventBus.emit(Event.Tailscale.DeviceDeleted(deviceId))
        clusterStateManager.update(clusterState) { it.tailscaleDeviceId = null }
    }
}
