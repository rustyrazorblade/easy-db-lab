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
            return ClientNetworks(values.map(::recorded).distinct())
        }

        /** A network `state.json` records; a value that does not parse was edited by hand or a bad [OPTION]. */
        private fun recorded(value: String): Ipv4Cidr =
            Ipv4Cidr.parse(value).getOrElse { cause ->
                throw IllegalStateException(
                    "state.json records client network '$value', which is not an IPv4 network (${cause.message}). " +
                        "Fix it in state.json, or run init again with a valid $OPTION <cidr>.",
                    cause,
                )
            }

        /**
         * The networks of a cluster easy-db-lab did not build: each node's pod CIDRs and its
         * InternalIP as a /32, then [overrides] (for a CNI that gives pods node-network addresses),
         * each once. A value that is not an IPv4 network is skipped.
         *
         * @throws IllegalArgumentException when no network is found, naming [OPTION].
         */
        fun probed(
            nodes: List<ProbedNode>,
            overrides: List<Ipv4Cidr>,
        ): ClientNetworks {
            val found =
                nodes.flatMap { node -> node.podCidrs + node.internalIps }.mapNotNull { Ipv4Cidr.parse(it).getOrNull() }
            val cidrs = (found + overrides).distinct()
            require(cidrs.isNotEmpty()) {
                "No client network found: the nodes report no IPv4 pod CIDR or InternalIP. Give one with $OPTION <cidr>."
            }
            return ClientNetworks(cidrs)
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
