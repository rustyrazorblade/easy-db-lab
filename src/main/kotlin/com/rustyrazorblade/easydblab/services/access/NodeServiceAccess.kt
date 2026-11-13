package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.ClusterState

/** The private address of the first node of [target]'s role, or a failure naming the role and Service. */
internal fun ClusterState.nodeAddressFor(target: ServiceTarget): String =
    hosts[target.nodeRole]?.firstOrNull()?.privateIp
        ?: error(
            "No ${target.nodeRole.serverType} node in cluster state runs ${target.namespace}/${target.service}. " +
                "Please ensure the environment is running.",
        )

/**
 * [ServiceAccess] for an AWS cluster: the node's private address through the per-client SOCKS tunnel.
 *
 * [tunnelPort] is asked on every [open], so a tunnel another command replaced is followed and no
 * process-wide port is held. It never reads or writes the JVM-global socks properties.
 *
 * @param loadState the workspace's cluster state, read on each open
 * @param tunnelPort the local port of the tunnel to use now (starting it, or only reusing it)
 */
class SocksServiceAccess(
    private val loadState: () -> ClusterState,
    private val tunnelPort: () -> Int,
) : ServiceAccess {
    override fun open(target: ServiceTarget): ServiceEndpoint {
        val host = loadState().nodeAddressFor(target)
        return ServiceEndpoint.ViaSocks(host, target.nodePort, tunnelPort())
    }
}

/**
 * [ServiceAccess] for a cluster whose private addresses this machine routes (Tailscale, or the
 * `direct` network mode): the node address with no proxy, and no tunnel started.
 *
 * @param loadState the workspace's cluster state, read on each open
 */
class DirectServiceAccess(
    private val loadState: () -> ClusterState,
) : ServiceAccess {
    override fun open(target: ServiceTarget): ServiceEndpoint = ServiceEndpoint.Plain(loadState().nodeAddressFor(target), target.nodePort)
}
