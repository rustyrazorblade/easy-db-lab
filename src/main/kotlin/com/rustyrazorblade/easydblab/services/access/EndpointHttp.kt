package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * This client with the proxy [endpoint] names set on it alone: a SOCKS proxy on
 * `127.0.0.1:<socksPort>` for [ServiceEndpoint.ViaSocks], none for [ServiceEndpoint.Plain]. The one
 * place an HTTP client of a cluster Service gets its route; the JVM-global socks properties are never
 * touched, and no shared selector can hand a client a stale tunnel port.
 */
fun OkHttpClient.forEndpoint(endpoint: ServiceEndpoint): OkHttpClient =
    newBuilder()
        .proxy(
            when (endpoint) {
                is ServiceEndpoint.Plain -> Proxy.NO_PROXY
                is ServiceEndpoint.ViaSocks ->
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(Constants.Proxy.TUNNEL_LISTEN_ADDRESS, endpoint.socksPort))
            },
        ).build()

/** The `http://host:port` base URL of this endpoint. */
fun ServiceEndpoint.baseUrl(): String = "http://$host:$port"
