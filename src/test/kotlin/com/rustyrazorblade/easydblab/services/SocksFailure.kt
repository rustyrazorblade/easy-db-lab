package com.rustyrazorblade.easydblab.services

import java.net.SocketException

/**
 * A [SocketException] as the JDK's SOCKS client throws it: with no type of its own, recognised by the
 * class of the frame that threw it. The real one is exercised in `ClusterReachSocksIntegrationTest`;
 * unit tests build this stand-in without opening a socket.
 */
fun socksFailure(message: String = "Malformed reply from SOCKS server"): SocketException =
    SocketException(message).also {
        it.stackTrace = arrayOf(StackTraceElement(ClusterReach.SOCKS_CLIENT_CLASS, "connect", null, -1))
    }
