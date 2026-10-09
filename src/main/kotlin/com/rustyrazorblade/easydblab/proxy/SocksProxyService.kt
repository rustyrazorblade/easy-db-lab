package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import java.time.Instant

/**
 * State of an active SOCKS5 proxy connection
 *
 * @property localPort The local port the proxy listens on
 * @property gatewayHost Full host info for the SSH gateway
 * @property startTime When the proxy was started
 * @property reused True when an already running, verified tunnel was kept instead of a new one started
 */
data class SocksProxyState(
    val localPort: Int,
    val gatewayHost: ClusterHost,
    val startTime: Instant,
    val reused: Boolean = false,
)

/**
 * Service interface for managing a SOCKS5 proxy via SSH dynamic port forwarding.
 *
 * The proxy is an OS process that persists across JVM restarts until `down` or `stop-socks` is
 * called. [ensureRunning] checks for a reusable existing process before starting a new one. Its port
 * is published only to the clients that opt in to the tunnel: the private
 * [com.rustyrazorblade.easydblab.Constants.Proxy.PORT_PROPERTY] for the CLI's own clients, and the
 * workspace's [ProxyEnvFile] for the shell-side tool wrappers.
 *
 * The implementation is thread-safe and gateway-agnostic.
 */
interface SocksProxyService {
    /**
     * Starts proxy if not running, or returns existing state if already running.
     * Idempotent - safe to call multiple times.
     *
     * If a proxy is already running to a different host, it will be stopped first.
     *
     * @param gatewayHost The host to use as SSH gateway for the proxy
     * @return The proxy state
     */
    fun ensureRunning(gatewayHost: ClusterHost): SocksProxyState

    /**
     * Explicitly start a new proxy connection.
     *
     * @param gatewayHost The host to use as SSH gateway for the proxy
     * @return The proxy state
     * @throws IllegalStateException if already running to a different host
     */
    fun start(gatewayHost: ClusterHost): SocksProxyState

    /**
     * Check if proxy is currently running and healthy.
     *
     * @return true if the proxy is running and the underlying session is open
     */
    fun isRunning(): Boolean

    /**
     * Get current proxy state.
     *
     * @return The current state, or null if not running
     */
    fun getState(): SocksProxyState?

    /**
     * Get the local port the proxy listens on.
     *
     * @return The configured local port
     */
    fun getLocalPort(): Int

    /**
     * Stops the recorded tunnel without touching the cluster: ends its process, deletes the proxy
     * state file, unpublishes the port, and removes the port from the proxy env file. The env file
     * keeps its Tailscale flag.
     *
     * @return the PID of the tunnel process that was stopped, or null when none was running
     */
    fun stop(): Int?
}
