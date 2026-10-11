package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * What the command executor does for a `@RequiresProxy` command before it runs: start or reuse the
 * SOCKS tunnel when the cluster is provisioned, its infrastructure is UP, and it is not a Tailscale
 * cluster.
 *
 * Whenever the cluster state can be read, it also records the cluster's Tailscale flag in the proxy
 * env file. A Tailscale cluster never starts a tunnel, so without this the tool wrappers and `env.sh`
 * would have no record of how to reach the cluster.
 *
 * A failure to establish the tunnel propagates: the annotation says the command cannot work without it.
 */
class ProxyPreflight(
    private val clusterStateManager: ClusterStateManager,
    private val socksProxyService: SocksProxyService,
    private val envFile: ProxyEnvFile,
) {
    /** Starts or reuses the tunnel when the cluster needs one; does nothing in a directory with no cluster. */
    fun ensureTunnel() {
        if (!clusterStateManager.exists()) return
        val state =
            runCatching { clusterStateManager.load() }.getOrElse { e ->
                log.debug(e) { "Could not load cluster state for proxy startup check" }
                return
            }
        envFile.recordTailscale(state.isTailscaleEnabled())
        if (!state.isInfrastructureUp() || state.isTailscaleEnabled()) return
        val controlHost = state.getControlHost() ?: return
        socksProxyService.ensureRunning(controlHost)
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
