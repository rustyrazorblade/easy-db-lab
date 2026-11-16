package com.rustyrazorblade.easydblab.network

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private val log = KotlinLogging.logger {}

/**
 * Answers whether this machine can open a TCP connection to an address, right now.
 *
 * This is the seam `up` uses to prove it has a route to the cluster's private network before it
 * relies on one. It is an interface so the decision that depends on the answer — abort with a
 * named cause, or continue — can be driven in tests without a real network.
 * Production wires in [SocketTcpReachabilityProbe].
 */
fun interface TcpReachabilityProbe {
    /**
     * @param host the address to dial, normally a private IP.
     * @param port the port to dial on [host].
     * @return true only if the connection was established within the probe's timeout.
     */
    fun isReachable(
        host: String,
        port: Int,
    ): Boolean
}

/**
 * The production [TcpReachabilityProbe]: one JDK socket connect with a short, fixed timeout.
 *
 * The timeout is deliberately small. This is a fail-fast check, not a wait-for-ready loop: the
 * target it dials is already known to be listening, so anything slower than a local network
 * round-trip means the packets are going nowhere.
 */
class SocketTcpReachabilityProbe(
    private val connectTimeoutMs: Int,
) : TcpReachabilityProbe {
    override fun isReachable(
        host: String,
        port: Int,
    ): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                true
            }
        } catch (e: IOException) {
            log.debug(e) { "TCP probe to $host:$port failed within $connectTimeoutMs ms" }
            false
        }
}
