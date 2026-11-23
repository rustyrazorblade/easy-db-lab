package com.rustyrazorblade.easydblab.configuration.profile

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One user profile: who owns the clusters, the one destination they run in, and the optional
 * features the profile has credentials for.
 *
 * A profile records exactly one [destination]. Choosing a different place to run clusters means a
 * different profile, never an option on a command. The type is provider neutral: each destination
 * type registers its own [Destination] subclass in the store's `SerializersModule`.
 *
 * @property email the owner, added to the tags of the resources the tool creates
 * @property tailscale the Tailscale OAuth client, absent when the profile never set one
 * @property axonOps the AxonOps organization and key, absent when the profile never set one
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Profile(
    val email: String,
    val destination: Destination,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tailscale: Tailscale? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val axonOps: AxonOps? = null,
) {
    /** Whether clusters of this profile join the tailnet; the rule `Init` and `profile show` share. */
    val isTailscaleEnabled: Boolean
        get() = tailscale?.enabled == true

    /** Whether AxonOps runs on clusters of this profile; the rule `Up`, `cassandra start` and `profile show` share. */
    val isAxonOpsEnabled: Boolean
        get() = axonOps?.enabled == true
}

/**
 * The place a profile's clusters run, with that place's settings and credentials.
 *
 * It is open, not sealed, so each destination type can live in its own module and register its own
 * subclass for the `type` key of the profile file. Only the AWS destination exists today.
 */
interface Destination {
    /** The value of the `type` key in the profile file, such as `aws`. */
    val kind: String

    /** The name shown to the user, such as `AWS`. */
    val displayName: String
}

/**
 * The Tailscale OAuth client a profile uses to put cluster nodes on the operator's tailnet.
 *
 * A block can be half set: an earlier profile file may hold the id without the secret. It is kept so
 * that setup can prefill it, and it stays disabled until both values are present.
 */
@Serializable
data class Tailscale(
    val clientId: String,
    val clientSecret: String,
    val tag: String = Constants.Tailscale.DEFAULT_DEVICE_TAG,
) {
    /** Both the id and the secret are needed to register a node. */
    val enabled: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        /** A block when either value is set, keeping a blank partner so setup can prefill it; else none. */
        fun orNull(
            clientId: String,
            clientSecret: String,
            tag: String = Constants.Tailscale.DEFAULT_DEVICE_TAG,
        ): Tailscale? = if (clientId.isNotBlank() || clientSecret.isNotBlank()) Tailscale(clientId, clientSecret, tag) else null
    }
}

/**
 * The AxonOps organization and agent key a profile uses to send database metrics to AxonOps.
 *
 * Like [Tailscale], a half-set block is kept and stays disabled.
 */
@Serializable
data class AxonOps(
    val org: String,
    val key: String,
) {
    /** The agent needs both the organization and the key. */
    val enabled: Boolean
        get() = org.isNotBlank() && key.isNotBlank()

    companion object {
        /** A block when either value is set, keeping a blank partner so setup can prefill it; else none. */
        fun orNull(
            org: String,
            key: String,
        ): AxonOps? = if (org.isNotBlank() || key.isNotBlank()) AxonOps(org, key) else null
    }
}

/**
 * Reads the active profile at the moment a value is needed.
 *
 * Nothing binds a [Profile] value: a value captured when a service was built goes stale as soon as a
 * command saves a new key pair or bucket. Callers ask this for the profile each time they use it.
 */
fun interface ProfileAccess {
    /** The profile as it is saved now. */
    fun current(): Profile
}

/**
 * Converts the flat profile file of earlier releases, and maps the current file's keys back to that
 * flat form for setup's prefill.
 *
 * Each destination type that existed before the current format supplies one; today only AWS does.
 * It keeps [ProfileStore] free of any destination's key names.
 */
interface LegacyProfileReader {
    /** Whether the earlier file's [values] hold credentials, which is what makes it a set-up profile. */
    fun holdsCredentials(values: Map<String, String>): Boolean

    /**
     * The profile the earlier file's [values] describe, or null when they hold no credentials.
     *
     * @throws IllegalArgumentException naming a value that is not valid, such as an unknown SSH transport
     */
    fun migrate(values: Map<String, String>): Profile?

    /**
     * The flat setup keys for the current file, given its scalar values keyed by dotted path
     * (`destination.location.region`). Setup prefills from these and reports a bad value in them, so
     * both file formats reach setup in one shape.
     */
    fun setupValues(paths: Map<String, String>): Map<String, String>
}
