package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.environment.ClientNetworkOrigin
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
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
         * The networks [state] records. With none recorded, on the nodes easy-db-lab builds
         * ([ClientNetworkOrigin.BuiltNetworks]) they are the VPC CIDR and the K3s cluster CIDR
         * easy-db-lab passes to K3s, followed by the networks added with [OPTION], each once.
         *
         * @throws IllegalStateException when the state records an empty list (`init` never writes
         * one, and rendering it would trust no client at all), or records none in an environment
         * whose networks easy-db-lab did not build: the VPC default would trust a network the
         * cluster may not use and refuse its real node and pod networks.
         */
        fun from(state: ClusterState): ClientNetworks {
            val recorded = state.clientNetworks
            check(recorded == null || recorded.isNotEmpty()) {
                "state.json records an empty client network list, so the database kits would accept no client. " +
                    "Remove clientNetworks from state.json, or run init again with $OPTION <cidr>."
            }
            val values = recorded ?: builtNetworks(state)
            return ClientNetworks(values.map { parse(it, ::recordedNetworkError) }.distinct())
        }

        /** The networks easy-db-lab built, for a state that records none; refused anywhere else. */
        private fun builtNetworks(state: ClusterState): List<String> {
            val contract = ClusterContract.of(state)
            check(contract.clientNetworkOrigin == ClientNetworkOrigin.BuiltNetworks) {
                "state.json records no client networks, and easy-db-lab does not know the networks of " +
                    "${contract.environment.displayName}, so the database kits would trust no client. " +
                    "Run init again with $OPTION <cidr> for each network the clients are in (the node network and the pod network)."
            }
            return listOf(state.initConfig?.cidr ?: Constants.Vpc.DEFAULT_CIDR, Constants.K3s.CLUSTER_CIDR) + state.extraClientNetworks
        }

        /**
         * [value] as an IPv4 network, or the exception [error] builds from it and the parse failure's
         * reason. The reason is in the message, so the cause is not chained: the user sees one line.
         */
        private fun parse(
            value: String,
            error: (value: String, reason: String?) -> RuntimeException,
        ): Ipv4Cidr = Ipv4Cidr.parse(value).getOrElse { cause -> throw error(value, cause.message) }

        /** A network `state.json` records that does not parse: edited by hand, or a bad [OPTION]. */
        private fun recordedNetworkError(
            value: String,
            reason: String?,
        ) = IllegalStateException(
            "state.json records client network '$value', which is not an IPv4 network ($reason). " +
                "Fix it in state.json, or run init again with a valid $OPTION <cidr>.",
        )

        /** A pod CIDR or InternalIP a node reports that is neither IPv4 nor IPv6: the probe misread it. */
        private fun probedNetworkError(
            value: String,
            reason: String?,
        ) = IllegalArgumentException(
            "A node reports client network '$value', which is not an IPv4 network ($reason). " +
                "Give the networks with $OPTION <cidr>.",
        )

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
            // must parse as IPv4, which fails with a clear message when it does not.
            val (ipv6, ipv4) = nodes.flatMap { node -> node.podCidrs + node.internalIps }.partition { it.contains(':') }
            val cidrs = (ipv4.map { parse(it, ::probedNetworkError) } + overrides).distinct()
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
         * Whether no node in [nodes] reports an IPv4 pod CIDR, so [probed] finds only node addresses.
         * That is what a CNI whose pods take node-network addresses (EKS VPC-CNI) looks like, and
         * its pods are then trusted only through an [OPTION] network.
         */
        fun reportsNoPodCidr(nodes: List<ProbedNode>): Boolean = nodes.flatMap { it.podCidrs }.none { ':' !in it }
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

/**
 * Whether this network is wide enough (/1 to [Constants.ClientNetworks.WIDE_PREFIX_WARNING_MAX])
 * that `init` warns before giving it passwordless trust as a client network.
 */
val Ipv4Cidr.isVeryWideClientNetwork: Boolean
    get() = prefixLength <= Constants.ClientNetworks.WIDE_PREFIX_WARNING_MAX
