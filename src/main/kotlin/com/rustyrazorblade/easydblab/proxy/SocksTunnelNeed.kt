package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure

/**
 * Whether the CLI reaches the cluster in [this] state through the SOCKS tunnel, so a command should
 * start one: only for [Exposure.Socks], and never under Tailscale (a state saved before `exposure`
 * existed reads as Socks even when Tailscale routes it). Direct and PortForward clusters never dial
 * the tunnel.
 */
fun ClusterState.reachedBySocksTunnel(): Boolean = exposure == Exposure.Socks && !isTailscaleEnabled()
