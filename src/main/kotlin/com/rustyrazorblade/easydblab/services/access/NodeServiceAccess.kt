package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute

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

/**
 * [ServiceAccess] over a route a long-running reader looked up for one cycle (`metrics stream`): the
 * node address of [state] through the route's tunnel port, or with no proxy. It never starts, stops
 * or replaces a tunnel, so the reader follows whatever tunnel another command keeps.
 *
 * @param state the cluster state the cycle read
 * @param route the route the cycle looked up
 */
class RouteServiceAccess(
    private val state: ClusterState,
    private val route: ObservabilityRoute,
) : ServiceAccess {
    override fun open(target: ServiceTarget): ServiceEndpoint {
        val host = state.nodeAddressFor(target)
        return when (route) {
            ObservabilityRoute.Direct -> ServiceEndpoint.Plain(host, target.nodePort)
            is ObservabilityRoute.Socks -> ServiceEndpoint.ViaSocks(host, target.nodePort, route.port)
        }
    }
}
