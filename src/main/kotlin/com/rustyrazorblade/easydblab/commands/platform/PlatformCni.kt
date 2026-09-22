package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.CiliumInspection
import com.rustyrazorblade.easydblab.services.CiliumInspectionService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Shows the cluster's pod-network datapath: the CNI selected at init and, on a Cilium cluster,
 * the agent's effective configuration and each node's ENI and IP allocation state.
 *
 * Uses println() directly — this is a read-only display command. No state changes; nothing an
 * external system needs to be aware of. On a Flannel cluster it prints one line and exits 0: there
 * is no Cilium to read back.
 */
@RequireProfileSetup
@Command(
    name = "cni",
    description = ["Show the pod-network datapath: CNI mode, Cilium configuration, and per-node ENI/IP allocation"],
)
class PlatformCni : PicoBaseCommand() {
    private val ciliumInspectionService: CiliumInspectionService by inject()

    override fun execute() {
        val cni = clusterState.initConfig?.cni ?: CniMode.Flannel
        if (cni != CniMode.Cilium) {
            println(FLANNEL_TEXT)
            return
        }

        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")

        val inspection = ciliumInspectionService.inspect(controlHost.toHost()).getOrThrow()
        println(buildReport(inspection, controlHost.privateIp))
    }

    companion object {
        const val FLANNEL_TEXT = "CNI: flannel (K3s built-in VXLAN overlay). No Cilium details to show."

        /**
         * Renders the Cilium report: configuration first, then one block per node.
         *
         * @param inspection What was read back from the cluster.
         * @param controlPrivateIp The control node's private IP, used to print the Hubble UI URL.
         */
        fun buildReport(
            inspection: CiliumInspection,
            controlPrivateIp: String,
        ): String {
            val config = inspection.config
            val header =
                """
                |CNI: cilium (ENI IPAM, native routing)
                |  Routing mode:            ${config.routingMode}
                |  IPAM mode:               ${config.ipamMode}
                |  kube-proxy replacement:  ${config.kubeProxyReplacement}
                |  Masquerade interfaces:   ${config.masqueradeInterfaces}
                |  IPv4 masquerade:         ${config.ipv4Masquerade}
                |  Native routing CIDR:     ${config.nativeRoutingCidr}
                |  Hubble UI:               http://$controlPrivateIp:${Constants.Cilium.HUBBLE_UI_NODE_PORT}
                |
                |Nodes (${inspection.nodes.size}):
                """.trimMargin()
            val nodes =
                inspection.nodes.joinToString("\n") { node ->
                    val subnets = if (node.subnetCidrs.isEmpty()) "(none)" else node.subnetCidrs.joinToString(", ")
                    """
                    |  ${node.name}
                    |    ENIs:           ${node.eniCount}
                    |    Subnet CIDRs:   $subnets
                    |    IPs allocated:  ${node.ipsAllocated}
                    |    IPs used:       ${node.ipsUsed}
                    |    IPs available:  ${node.ipsAvailable}
                    """.trimMargin()
                }
            return if (inspection.nodes.isEmpty()) "$header\n  (no CiliumNode objects yet)" else "$header\n$nodes"
        }
    }
}
