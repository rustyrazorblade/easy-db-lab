package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.NetworkMode

/**
 * Whether this machine reaches the cluster in [this] state with no tunnel at all, because its
 * environment is in [NetworkMode.Direct] (K3s in Docker, a kubeconfig cluster).
 */
fun ClusterState.isDirectNetwork(): Boolean = ClusterContract.of(this).networkMode == NetworkMode.Direct

/**
 * Whether the CLI reaches the cluster in [this] state through the SOCKS tunnel, so a command should
 * start one: only for [Exposure.Socks] in [NetworkMode.Tunnel], and never under Tailscale (a state
 * saved before `exposure` existed reads as Socks even when Tailscale routes it). Direct and
 * PortForward clusters never dial the tunnel.
 */
fun ClusterState.reachedBySocksTunnel(): Boolean = exposure == Exposure.Socks && !isTailscaleEnabled() && !isDirectNetwork()

/**
 * Records how the cluster in [state] is reached, the Tailscale flag and the direct network mode, so
 * the shell-side tools know without a tunnel to ask. Every writer of the flags goes through here,
 * so a key deleted by hand is back before the next command's logic runs.
 */
fun ProxyEnvFile.recordReachability(state: ClusterState) {
    recordTailscale(state.isTailscaleEnabled())
    recordDirect(state.isDirectNetwork())
}
