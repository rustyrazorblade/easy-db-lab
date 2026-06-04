package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import java.time.Instant

/**
 * State of an active SOCKS5 proxy connection
 *
 * @property localPort The local port the proxy listens on
 * @property gatewayHost Full host info for the SSH gateway
 * @property startTime When the proxy was started
 * @property connectionCount Tracks usage in server mode
 */
data class SocksProxyState(
    val localPort: Int,
    val gatewayHost: ClusterHost,
    val startTime: Instant,
)

/**
 * Service interface for managing a SOCKS5 proxy via SSH dynamic port forwarding.
 *
 * The proxy is an OS process that persists across JVM restarts until `down` is called.
 * [ensureRunning] checks for a reusable existing process before starting a new one.
 * When the proxy starts, JVM system properties are set so all Java socket-layer clients
 * route through the tunnel automatically.
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
}
