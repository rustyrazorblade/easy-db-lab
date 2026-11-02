package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState

/** How a reader reaches the control node's observability services for one request. */
sealed interface ObservabilityRoute {
    /** Straight to the private address: Tailscale routes it. */
    data object Direct : ObservabilityRoute

    /** Through the recorded SOCKS tunnel listening on [port]. */
    data class Socks(
        val port: Int,
    ) : ObservabilityRoute
}

/** The route a reader can use now, or why it has none. */
sealed interface RouteLookup {
    /** Use [route]. */
    data class Usable(
        val route: ObservabilityRoute,
    ) : RouteLookup

    /** No route; [reason] tells the user what to do. */
    data class Unavailable(
        val reason: String,
    ) : RouteLookup
}

/**
 * Finds the route to the observability services without starting, stopping or replacing a tunnel.
 *
 * A long-running reader such as `metrics stream` looks the route up every cycle, so it follows a
 * tunnel that another command replaced and never repairs one itself.
 */
fun interface ObservabilityRoutes {
    /** The route for a cluster in [state], looked up now. */
    fun current(state: ClusterState): RouteLookup
}

/**
 * [ObservabilityRoutes] from the workspace's proxy env file: Tailscale goes direct, otherwise the
 * tunnel recorded as `EDL_SOCKS_PORT` / `EDL_SOCKS_PID`, while that PID is still the tunnel script
 * for that port and the workspace's `sshConfig`.  It reads no JSON.
 *
 * @param envFile the workspace's `.socks5-proxy.env`
 * @param sshConfig the absolute path of the workspace's `sshConfig`, which the script was started with
 * @param control the identity check for a recorded tunnel PID
 */
class RecordedTunnelRoutes(
    private val envFile: ProxyEnvFile,
    private val sshConfig: String,
    private val control: TunnelProcessControl,
) : ObservabilityRoutes {
    override fun current(state: ClusterState): RouteLookup {
        if (state.isTailscaleEnabled()) return RouteLookup.Usable(ObservabilityRoute.Direct)
        val tunnel = envFile.read().tunnel ?: return RouteLookup.Unavailable(NO_TUNNEL)
        return when (control.status(tunnel.pid, tunnel.port, sshConfig)) {
            RecordedTunnelStatus.RUNNING -> RouteLookup.Usable(ObservabilityRoute.Socks(tunnel.port))
            RecordedTunnelStatus.GONE, RecordedTunnelStatus.PID_REUSED -> RouteLookup.Unavailable(NO_TUNNEL)
        }
    }

    private companion object {
        const val NO_TUNNEL = "no live SOCKS tunnel is recorded; run 'easy-db-lab socks start'"
    }
}
