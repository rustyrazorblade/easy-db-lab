package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract

/**
 * How this machine reaches the cluster in [this] state: the [ClusterContract]'s network mode, which
 * is the recorded [ClusterState.networkMode] on EC2 and [NetworkMode.Direct] in every other
 * environment (K3s in Docker, a kubeconfig cluster), whatever the state records. Every tunnel
 * decision and every write of `EDL_NETWORK_MODE` reads this, never the field alone.
 */
fun ClusterState.effectiveNetworkMode(): NetworkMode = ClusterContract.of(this).networkMode

/**
 * How the CLI reaches a cluster Service in [this] state: the [ClusterContract]'s exposure, which is the
 * recorded [ClusterState.exposure] on EC2 and [Exposure.PortForward] in every other environment, whatever
 * the state records. Every exposure decision reads this, never the field alone.
 */
fun ClusterState.effectiveExposure(): Exposure = ClusterContract.of(this).exposure

/**
 * Whether the CLI reaches the cluster in [this] state through the SOCKS tunnel, so a command should
 * start one: only for [Exposure.Socks] in a mode that [NetworkMode.needsTunnel]. A Direct or
 * PortForward cluster never dials the tunnel, and neither does one in the `tailscale` or `direct`
 * network mode.
 */
fun ClusterState.reachedBySocksTunnel(): Boolean = effectiveExposure() == Exposure.Socks && effectiveNetworkMode().needsTunnel
