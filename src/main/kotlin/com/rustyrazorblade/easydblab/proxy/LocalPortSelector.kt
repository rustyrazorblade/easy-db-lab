package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

private val log = KotlinLogging.logger {}

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
 * Production [LocalPortSelector]: [preferred] if it is free on the loopback interface, otherwise an
 * OS-assigned ephemeral port.
 *
 * @param preferred the port to try first. The integration tests pass a port known to be bound, to
 *   drive the fallback branch deterministically instead of binding the hardcoded default port (a
 *   fixed-port collision risk on CI — issue #750).
 */
class LoopbackPortSelector(
    private val preferred: Int = Constants.Proxy.DEFAULT_SOCKS5_PORT,
) : LocalPortSelector {
    override fun select(): Int =
        if (isLoopbackPortFree(preferred)) {
            preferred
        } else {
            log.debug { "Port $preferred is in use on the loopback interface, selecting an available port" }
            ServerSocket(0).use { it.localPort }
        }

    /**
     * Probes whether [port] is free the way `ssh -D` binds it: on the loopback interface, not the
     * wildcard address.
     *
     * A plain `ServerSocket(port)` binds the wildcard address with `SO_REUSEADDR` enabled (Java's
     * default for a server socket). Under `SO_REUSEADDR`, a wildcard bind coexists with an existing
     * loopback-scoped listener, so the probe reports the port as free even though `ssh -D` — which
     * binds `127.0.0.1` and `::1` — cannot use it. That made the fallback dead code against this
     * tool's own proxy: a second datacenter kept retrying the busy port and ssh failed with
     * "bind [::1]:<port>: Address already in use".
     *
     * This probe instead binds each loopback address with `SO_REUSEADDR` disabled, matching ssh. The
     * port is free only if every loopback address binds cleanly; a live loopback listener triggers
     * the ephemeral fallback.
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
     * The loopback addresses `ssh -D` binds: the IPv4 and IPv6 loopback. Both are resolved from their
     * literal forms, so neither depends on name resolution.
     */
    private fun loopbackProbeAddresses(): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
}
