package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * How this machine opens TCP connections to port 22 on cluster nodes.
 *
 * SSH is the protocol for every node interaction either way; only the path to port 22 changes.
 * [Direct] dials the node's public IP, which needs the operator's network to reach that IP on
 * port 22. [Ssm] tunnels each connection through AWS Systems Manager Session Manager, which needs
 * no inbound port at all. That is the escape hatch for networks whose egress proxy blocks or
 * re-routes port 22 to freshly provisioned, never pre-registered addresses.
 *
 * It is a property of the operator's network, so it lives in the user profile rather than in
 * cluster state: a cluster is provisioned identically under either transport.
 *
 * @property configValue the value written to the profile and accepted from it and the setup prompt
 */
enum class SshTransport(
    @get:JsonValue val configValue: String,
) {
    Direct("direct"),
    Ssm("ssm"),
    ;

    companion object {
        /**
         * Parses a prompt answer or profile value, ignoring case and surrounding whitespace; null if
         * unrecognized, so the setup prompt can ask again.
         */
        fun parse(value: String): SshTransport? = entries.firstOrNull { it.configValue.equals(value.trim(), ignoreCase = true) }

        /**
         * The profile file's decoder. Jackson is used only because [User] is still read with it.
         * It accepts what [parse] accepts, so a hand-edited `SSM` reads the same as the prompt
         * would take it, and rejects anything else rather than letting a typo fall back silently.
         *
         * @throws IllegalArgumentException naming [value] and the valid choices when it is not a transport
         */
        @JvmStatic
        @JsonCreator
        fun fromProfile(value: String): SshTransport =
            requireNotNull(parse(value)) {
                "'$value' is not an SSH transport. Valid choices: ${entries.joinToString(", ") { it.configValue }}"
            }
    }
}
