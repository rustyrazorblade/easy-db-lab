package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ProbedNode
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.network.Ipv4Cidr

/**
 * Finds the client networks of a cluster easy-db-lab did not build, where no VPC is known: each
 * node's pod CIDRs and InternalIP, read from the Kubernetes API, plus the networks the user gave
 * with `init --client-network` (for a CNI, such as EKS VPC-CNI, whose pods take node-network
 * addresses and so have no pod CIDR). The result is recorded in state at `init`.
 *
 * `Init` calls it, before anything is written, when it is given a brought cluster's API. It is not
 * called on the nodes easy-db-lab builds, whose networks are the VPC and the K3s cluster CIDR
 * ([ClientNetworks.from]). The `init --kubeconfig` option that hands `Init` that API is #1082.
 *
 * @param clientFactory builds the client for the cluster named by the [KubeApi]
 */
class ClientNetworkProbe(
    private val clientFactory: KubernetesClientFactory,
) {
    /** The client networks of the cluster [api] names, with [overrides] added. */
    fun probe(
        api: KubeApi,
        overrides: List<Ipv4Cidr>,
    ): ClientNetworkProbeResult =
        clientFactory.createClient(api).use { client ->
            val nodes =
                client.nodes().list().items.map { node ->
                    ProbedNode(
                        podCidrs =
                            node.spec
                                ?.podCIDRs
                                .orEmpty()
                                .ifEmpty { listOfNotNull(node.spec?.podCIDR) },
                        internalIps =
                            node.status
                                ?.addresses
                                .orEmpty()
                                .filter { it.type == INTERNAL_IP }
                                .map { it.address },
                    )
                }
            ClientNetworkProbeResult(ClientNetworks.probed(nodes, overrides), ClientNetworks.reportsNoPodCidr(nodes))
        }

    private companion object {
        const val INTERNAL_IP = "InternalIP"
    }
}

/**
 * What [ClientNetworkProbe] found: the [networks] to record, and whether no node reported an IPv4
 * pod CIDR ([noPodCidr]), so the networks hold only node addresses and the overrides. `init` warns
 * on that, since pods with addresses outside them (EKS VPC-CNI) would be refused.
 */
data class ClientNetworkProbeResult(
    val networks: ClientNetworks,
    val noPodCidr: Boolean,
)
