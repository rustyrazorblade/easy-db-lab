package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Chooses the local port a new SOCKS5 proxy's `ssh -D` listener will bind.
 *
 * This is the injectable seam [ProcessSocksProxyService] uses to pick a port. It exists so the
 * service's start decisions (which port each ssh attempt is handed, and what happens when that bind
 * fails) can be driven in unit tests with a scripted sequence of ports, without binding real sockets.
 * Production wires in [LoopbackPortSelector].
 */
fun interface LocalPortSelector {
    /** @return a port that is free on the loopback interface at the time of the call. */
    fun select(): Int
}

/**
 * Production [LocalPortSelector]: a random port from [Constants.Proxy.LOCAL_PORT_RANGE] that is free
 * on the loopback interface, with no fixed port preferred, so two workspaces are unlikely to want the
 * same port. The range lies outside the OS ephemeral ranges (see [Constants.Proxy.LOCAL_PORT_RANGE]),
 * so when [attempts] random ports are all taken it fails rather than take an OS-assigned port, which
 * would be ephemeral.
 *
 * Two workspaces can still pick the same free port at the same moment; the service's bind-race retry
 * handles that.
 *
 * @param candidates the next port to try; the integration tests pass known ports to drive each branch
 * @param attempts how many candidates are tried before the selection fails
 */
class LoopbackPortSelector(
    private val candidates: () -> Int = { Constants.Proxy.LOCAL_PORT_RANGE.random() },
    private val attempts: Int = Constants.Proxy.LOCAL_PORT_ATTEMPTS,
) : LocalPortSelector {
    /** @throws IllegalStateException when [attempts] random ports of the range are all taken */
    override fun select(): Int =
        generateSequence(candidates).take(attempts).firstOrNull { isLoopbackPortFree(it) }
            ?: error(
                "No free local port for the SOCKS5 tunnel: $attempts random ports in " +
                    "${Constants.Proxy.LOCAL_PORT_RANGE.first}-${Constants.Proxy.LOCAL_PORT_RANGE.last} were all in use on the " +
                    "loopback interface. Free some ports in that range, then run 'easy-db-lab start-socks'.",
            )

    /**
     * Probes whether [port] is free the way `ssh -D` binds it: on the loopback interface, not the
     * wildcard address.
     *
     * A plain `ServerSocket(port)` binds the wildcard address with `SO_REUSEADDR` enabled (Java's
     * default for a server socket). Under `SO_REUSEADDR`, a wildcard bind coexists with an existing
     * loopback-scoped listener, so the probe reports the port as free even though `ssh -D` cannot
     * use it. That made the fallback dead code against this tool's own proxy: a second datacenter
     * kept retrying the busy port and ssh failed to bind it.
     *
     * This probe instead binds each loopback address with `SO_REUSEADDR` disabled, matching ssh. The
     * port is free only if every loopback address binds cleanly. The tunnel binds only `127.0.0.1`,
     * but a port another process holds on `::1` is passed over too, so a client that says
     * `localhost` cannot reach that process.
     */
    private fun isLoopbackPortFree(port: Int): Boolean =
        loopbackProbeAddresses().all { address ->
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(address, port))
                    true
                }
            } catch (_: IOException) {
                false
            }
        }

    /**
     * The loopback addresses a free port must be free on: the IPv4 loopback the tunnel binds, and the
     * IPv6 loopback. Both are resolved from their literal forms, so neither depends on name resolution.
     */
    private fun loopbackProbeAddresses(): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
}
