package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * [ProxySelector] that routes connections through the easy-db-lab SOCKS5 tunnel when one is active.
 *
 * The active proxy port is read from [Constants.Proxy.PORT_PROPERTY] on every selection: when it is
 * present (proxy running) a SOCKS proxy to `127.0.0.1:<port>` is returned; when it is absent (no
 * proxy started — e.g. Tailscale, or before the proxy starts) `NO_PROXY` is returned so the
 * connection goes direct. Recomputing per call means a client built before the proxy starts still
 * picks it up.
 *
 * This is installed only on the cluster HTTP clients (via [ProxiedHttpClientFactory]), so it never
 * affects AWS or other non-cluster traffic — unlike the global `socksProxyHost` property, which would
 * capture every socket in the JVM.
 */
class Socks5ProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val port = System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()
        return if (port != null) {
            listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } else {
            listOf(Proxy.NO_PROXY)
        }
    }

    // No proxy-failure bookkeeping: the proxy is recomputed from the system property on each select().
    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) = Unit
}
