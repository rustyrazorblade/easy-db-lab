package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import kotlinx.serialization.json.Json

/**
 * The networks a passwordless database kit accepts clients from: ClickHouse's `default` user
 * networks and Postgres's `pg_hba` trust lines. Clients reach a kit pod from a node (NodePort
 * traffic is SNATed to a node or a pod gateway address) or from another pod, so the set is the
 * node network and the pod network of the environment, never a fixed `10.0.0.0/8`.
 *
 * @property cidrs the networks, in the order the kit configuration lists them
 */
data class ClientNetworks(
    val cidrs: List<Ipv4Cidr>,
) {
    /** The networks as a JSON list of CIDR strings, for ClickHouse's `networks/ip`. */
    fun toJson(): String = Json.encodeToString(cidrs.map { it.value })

    /** One `pg_hba` trust line per network, as a JSON list. */
    fun toHbaJson(): String = Json.encodeToString(cidrs.map { "host all all ${it.value} trust" })

    companion object {
        /** The option a user adds a client network with. */
        const val OPTION = "--client-network"

        /**
         * Parses an [OPTION] value. Every client network gets passwordless trust, so `/0` (every
         * address, more likely a typo than intent) is refused; any narrower prefix is accepted.
         *
         * @throws IllegalArgumentException naming the value when it is not an IPv4 network or is `/0`
         */
        fun parseOption(value: String): Ipv4Cidr {
            val cidr = Ipv4Cidr.parse(value).getOrThrow()
            require(cidr.prefixLength > 0) {
                "Invalid $OPTION '$value': a /0 network would give every address passwordless access to the " +
                    "database kits. Give the network the clients are in."
            }
            return cidr
        }

        /**
         * The networks [state] records, or, for a state that records none (the nodes easy-db-lab
         * builds), the VPC CIDR and the K3s cluster CIDR easy-db-lab passes to K3s, followed by
         * the networks added with [OPTION], each once.
         */
        fun from(state: ClusterState): ClientNetworks {
            val recorded = state.clientNetworks
            val values =
                recorded
                    ?: (
                        listOf(state.initConfig?.cidr ?: Constants.Vpc.DEFAULT_CIDR, Constants.K3s.CLUSTER_CIDR) +
                            state.extraClientNetworks
                    )
            return ClientNetworks(values.map(::parseRecorded).distinct())
        }

        /**
         * A network `state.json` records; a value that does not parse was edited by hand or a bad [OPTION].
         * The parse reason is in the message, so the cause is not chained: the user sees one line.
         */
        private fun parseRecorded(value: String): Ipv4Cidr =
            Ipv4Cidr.parse(value).getOrElse { cause ->
                throw IllegalStateException(
                    "state.json records client network '$value', which is not an IPv4 network (${cause.message}). " +
                        "Fix it in state.json, or run init again with a valid $OPTION <cidr>.",
                )
            }

        /**
         * The networks of a cluster easy-db-lab did not build: each node's pod CIDRs and its
         * InternalIP as a /32, then [overrides] (for a CNI that gives pods node-network addresses),
         * each once. An IPv6 value (a dual-stack node reports both families) is skipped; any other
         * value that is not an IPv4 network means the probe read something wrong, and fails.
         *
         * @throws IllegalArgumentException when a value is neither IPv4 nor IPv6, or no network is
         * found, naming [OPTION] and, on an IPv6-only cluster, the IPv6 values it skipped.
         */
        fun probed(
            nodes: List<ProbedNode>,
            overrides: List<Ipv4Cidr>,
        ): ClientNetworks {
            // A ':' only ever appears in IPv6, so it is enough to set those aside: everything else
            // must parse as IPv4 in parseProbed, which fails with a clear message when it does not.
            val (ipv6, ipv4) = nodes.flatMap { node -> node.podCidrs + node.internalIps }.partition { it.contains(':') }
            val cidrs = (ipv4.map(::parseProbed) + overrides).distinct()
            require(cidrs.isNotEmpty()) {
                val skipped =
                    when (ipv6.isEmpty()) {
                        true -> ""
                        false -> " (skipped ${ipv6.size} IPv6 values, which the database kits cannot use: ${ipv6.joinToString(", ")})"
                    }
                "No client network found: the nodes report no IPv4 pod CIDR or InternalIP$skipped. Give one with $OPTION <cidr>."
            }
            return ClientNetworks(cidrs)
        }

        /**
         * A pod CIDR or InternalIP a node reports; one that is not IPv4 or IPv6 was misread. The parse
         * reason is in the message, so the cause is not chained: the user sees one line.
         */
        private fun parseProbed(value: String): Ipv4Cidr =
            Ipv4Cidr.parse(value).getOrElse { cause ->
                throw IllegalArgumentException(
                    "A node reports client network '$value', which is not an IPv4 network (${cause.message}). " +
                        "Give the networks with $OPTION <cidr>.",
                )
            }
    }
}

/**
 * What a node reports that names a client network: its `spec.podCIDRs` and its `InternalIP`
 * addresses.
 */
data class ProbedNode(
    val podCidrs: List<String>,
    val internalIps: List<String>,
)
