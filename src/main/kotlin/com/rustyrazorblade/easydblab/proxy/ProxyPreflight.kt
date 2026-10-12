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
 *
 * The tunnel it starts or reuses is recorded for the command about to run ([tunnelForCommand]), so
 * a command that reports the tunnel (`up`) says what happened in this invocation. Asking the service
 * again would find the tunnel the preflight just started and call it "already running". The record
 * is per thread, like [ProxyAvailability], and the executor clears it before every command.
 */
class ProxyPreflight(
    private val clusterStateManager: ClusterStateManager,
    private val socksProxyService: SocksProxyService,
    private val envFile: ProxyEnvFile,
) {
    private val obtained = ThreadLocal<SocksProxyState?>()

    /**
     * The tunnel this thread's last [ensureTunnel] started or reused, with its `reused` flag as the
     * service returned it; null when it obtained none or [clear] has run since.
     */
    fun tunnelForCommand(): SocksProxyState? = obtained.get()

    /** Forgets the recorded tunnel. The executor calls it before every command. */
    fun clear() {
        obtained.remove()
    }

    /** Starts or reuses the tunnel when the cluster needs one; does nothing in a directory with no cluster. */
    fun ensureTunnel() {
        obtained.remove()
        if (!clusterStateManager.exists()) return
        val state =
            runCatching { clusterStateManager.load() }.getOrElse { e ->
                log.debug(e) { "Could not load cluster state for proxy startup check" }
                return
            }
        envFile.recordTailscale(state.isTailscaleEnabled())
        if (!state.isInfrastructureUp() || state.isTailscaleEnabled()) return
        val controlHost = state.getControlHost() ?: return
        obtained.set(socksProxyService.ensureRunning(controlHost))
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
