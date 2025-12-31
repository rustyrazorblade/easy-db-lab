package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Factory interface for creating HTTP clients.
 *
 * Abstracts the client creation to allow different configurations
 * (direct connection, SOCKS proxy, etc.)
 */
interface HttpClientFactory {
    /**
     * Create an OkHttp client configured for proxy access.
     *
     * @return Configured OkHttpClient ready for HTTP requests
     */
    fun createClient(): OkHttpClient
}

/**
 * HTTP client factory that configures SOCKS5 proxy for access to private endpoints.
 *
 * This is used when accessing services running on private IPs through an SSH tunnel.
 * Uses OkHttp which has native SOCKS5 proxy support.
 *
 * @property socksProxyService The SOCKS proxy service for establishing connections
 */
class ProxiedHttpClientFactory(
    private val socksProxyService: SocksProxyService,
) : HttpClientFactory {
    companion object {
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
    }

    override fun createClient(): OkHttpClient {
        val proxyPort = socksProxyService.getLocalPort()
        log.info { "Creating OkHttp client with SOCKS5 proxy on 127.0.0.1:$proxyPort" }

        // Use explicit 127.0.0.1 to match the SOCKS proxy binding and avoid IPv4/IPv6 issues
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))

        return OkHttpClient
            .Builder()
            .proxy(proxy)
            .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
