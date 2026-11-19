package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.rustyrazorblade.easydblab.Constants

/**
 * How this machine reaches the cluster: the one value that decides whether the SOCKS tunnel is used.
 *
 * `init` records it in `state.json` (`networkMode`), and the CLI copies it into the workspace's proxy
 * env file (`EDL_NETWORK_MODE`) for the tool wrappers and `env.sh`. Both places use the same wire
 * [id], so one name means one thing everywhere. It replaced the `tailscaleActive` boolean, which
 * could not say "direct", and Patrick's second `EDL_NETWORK_MODE=direct` flag, which allowed
 * combinations that meant nothing.
 *
 * The name says how the cluster is reached, not which cloud runs it, so a later execution
 * environment (K3s in Docker, an existing cluster) records [Direct] without a provider type.
 *
 * @property id the value written to `state.json` and the env file
 */
enum class NetworkMode(
    @get:JsonValue val id: String,
) {
    /** Through the SOCKS tunnel the CLI manages. */
    Socks("socks"),

    /** Over the tailnet, to the cluster's private addresses. */
    Tailscale("tailscale"),

    /** The cluster API is reachable from this machine as it is, with no tunnel. */
    Direct(Constants.Proxy.NETWORK_MODE_DIRECT),
    ;

    /** True only in [Socks] mode: every other mode connects directly and never starts the tunnel. */
    val needsTunnel: Boolean get() = this == Socks

    companion object {
        /** The mode whose wire id is [id]; null when [id] is not one. */
        fun fromId(id: String): NetworkMode? = entries.firstOrNull { it.id == id }

        /** The known wire ids, in declaration order, for error messages. */
        val knownIds: String get() = entries.joinToString(", ") { it.id }

        /**
         * The `state.json` decoder. [ClusterStateManager.load] checks the value first and fails with a
         * message that names the file, so this only guards a state mapped some other way.
         *
         * @throws IllegalArgumentException when [id] is not a known mode
         */
        @JvmStatic
        @JsonCreator
        fun fromState(id: String): NetworkMode = requireNotNull(fromId(id)) { "Unknown networkMode '$id': the known values are $knownIds" }
    }
}
