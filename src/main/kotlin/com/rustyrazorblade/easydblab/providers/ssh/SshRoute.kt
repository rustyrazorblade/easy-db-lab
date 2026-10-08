package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import java.time.Duration

/** The address and port the in-process SSH client dials to reach a host's sshd. */
data class SshEndpoint(
    val address: String,
    val port: Int,
)

/**
 * How this machine reaches a cluster node's sshd: one implementation per SSH transport.
 *
 * Both SSH clients take their route from here, so the transport is decided once, in Koin, rather
 * than separately by each client. The in-process client dials [endpoint]. The OpenSSH paths (the
 * SOCKS tunnel and the `env.sh` helpers) use the generated `sshConfig`, which carries
 * [proxyCommand] for each host.
 */
interface SshRoute : AutoCloseable {
    /** Where the in-process client connects for [host]. */
    fun endpoint(host: Host): SshEndpoint

    /** The ssh_config `ProxyCommand` for [host], or null when ssh should dial the host's `Hostname` itself. */
    fun proxyCommand(host: Host): String?

    /** How many times, 500ms apart, a fresh OpenSSH SOCKS tunnel over this route is probed before it counts as failed. */
    val tunnelVerifyAttempts: Int

    /**
     * How long the in-process client waits for key exchange and authentication over this route,
     * or null for MINA's own default (120s).
     */
    val authTimeout: Duration?
        get() = null

    /**
     * Reports that a connection to [host] through [endpoint] failed, so the next [endpoint] call
     * must not hand out the same path. A route that started something to reach the host stops it;
     * a route with nothing to retire does nothing.
     */
    fun invalidate(host: Host) = Unit

    /** Releases anything the route started to make hosts reachable. */
    override fun close() = Unit
}

/**
 * The `direct` transport: dial the node's public IP. Nothing is started, so there is nothing to
 * close or invalidate.
 */
class DirectSshRoute(
    private val sshPort: Int,
) : SshRoute {
    override fun endpoint(host: Host): SshEndpoint = SshEndpoint(host.public, sshPort)

    override fun proxyCommand(host: Host): String? = null

    override val tunnelVerifyAttempts: Int = Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS
}
