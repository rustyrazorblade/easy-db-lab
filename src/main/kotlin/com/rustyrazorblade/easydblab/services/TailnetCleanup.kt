package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * How removing a control node's device from the tailnet ended.
 */
sealed interface DeviceRemoval {
    /** No device ID was recorded, so there was nothing to remove. */
    data object NoneRecorded : DeviceRemoval

    /** The device was deleted from the tailnet. */
    data class Removed(
        val deviceId: String,
    ) : DeviceRemoval

    /** The device is still on the tailnet; [reason] says why and how to fix it. */
    data class Failed(
        val reason: String,
    ) : DeviceRemoval
}

/**
 * How deleting a cluster's Tailscale auth key ended.
 */
sealed interface AuthKeyRemoval {
    /** No auth key ID was recorded, so there was nothing to delete. */
    data object NoneRecorded : AuthKeyRemoval

    /** The profile has no Tailscale OAuth credentials, so the key cannot be deleted. */
    data class NoCredentials(
        val keyId: String,
    ) : AuthKeyRemoval

    /** The auth key was deleted. */
    data class Removed(
        val keyId: String,
    ) : AuthKeyRemoval

    /** Tailscale refused the delete; [reason] is its error. */
    data class Failed(
        val keyId: String,
        val reason: String,
    ) : AuthKeyRemoval
}

/**
 * Removes what a cluster left on the tailnet when `down` tears it down: the control node's device
 * and the auth key `tailscale start` minted. It owns the credential checks and the failure text,
 * and returns a result, so `down` decides what each ending means for the teardown.
 */
class TailnetCleanup(
    private val tailscaleService: TailscaleService,
    private val user: User,
    private val eventBus: EventBus,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Removes the control node's device from the tailnet, by the device ID `tailscale start`
     * recorded. The instance is gone, but its device outlives it, and every cluster's control node
     * registers under the same hostname, so the recorded ID is the only precise handle on it.
     */
    fun removeDevice(deviceId: String?): DeviceRemoval {
        if (deviceId.isNullOrBlank()) return DeviceRemoval.NoneRecorded

        val clientId = user.tailscaleClientId
        val clientSecret = user.tailscaleClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return DeviceRemoval.Failed(
                "Tailscale device $deviceId (the control node) was not removed from the tailnet: " +
                    "no Tailscale OAuth credentials are configured. Configure them with " +
                    "'easy-db-lab profile setup' and run 'easy-db-lab down' again, or remove the device at " +
                    "https://login.tailscale.com/admin/machines.",
            )
        }

        return try {
            tailscaleService.deleteDevice(clientId, clientSecret, deviceId)
            eventBus.emit(Event.Tailscale.DeviceDeleted(deviceId))
            DeviceRemoval.Removed(deviceId)
        } catch (e: TailscaleApiException) {
            DeviceRemoval.Failed("Tailscale device $deviceId (the control node) was not removed from the tailnet: ${e.message}")
        }
    }

    /**
     * Deletes the Tailscale auth key [keyId]. Unlike the device, a key left behind does not fail
     * `down`: the caller only needs to know what happened.
     */
    fun deleteAuthKey(keyId: String?): AuthKeyRemoval {
        if (keyId.isNullOrBlank()) {
            log.debug { "No Tailscale auth key to delete" }
            return AuthKeyRemoval.NoneRecorded
        }

        val clientId = user.tailscaleClientId
        val clientSecret = user.tailscaleClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn { "Tailscale OAuth credentials not configured, cannot delete auth key $keyId" }
            return AuthKeyRemoval.NoCredentials(keyId)
        }

        return try {
            tailscaleService.deleteAuthKey(clientId, clientSecret, keyId)
            eventBus.emit(Event.Tailscale.AuthKeyDeleted(keyId))
            AuthKeyRemoval.Removed(keyId)
        } catch (e: TailscaleApiException) {
            log.warn(e) { "Failed to delete Tailscale auth key: $keyId" }
            AuthKeyRemoval.Failed(keyId, e.messageOrString())
        }
    }
}
