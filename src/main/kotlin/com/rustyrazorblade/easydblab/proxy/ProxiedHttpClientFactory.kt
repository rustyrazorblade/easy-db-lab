package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
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
 *
 * Implementations should cache and reuse clients. Call [close] to release
 * background threads (HTTP/2 readers, connection pool) when done.
 */
interface HttpClientFactory : AutoCloseable {
    /**
     * Get an OkHttp client configured for cluster access.
     * Returns a direct client when Tailscale is active, or a SOCKS-proxied client otherwise.
     * Implementations should return a cached singleton client.
     *
     * @return Configured OkHttpClient ready for HTTP requests
     */
    fun createClient(): OkHttpClient
}

/**
 * HTTP client factory that routes cluster traffic through a SOCKS5 proxy or directly,
 * depending on whether Tailscale was active at init time.
 *
 * @property socksProxyService The SOCKS proxy service for non-Tailscale connections
 * @property clusterStateManager Used to check [tailscaleActive] at client creation time
 */
class ProxiedHttpClientFactory(
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
) : HttpClientFactory {
    companion object {
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
    }

    @Volatile
    private var cachedClient: OkHttpClient? = null

    override fun createClient(): OkHttpClient {
        cachedClient?.let { return it }

        synchronized(this) {
            cachedClient?.let { return it }

            val client =
                if (clusterStateManager.load().tailscaleActive) {
                    log.info { "Creating direct OkHttp client (Tailscale active)" }
                    OkHttpClient
                        .Builder()
                        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build()
                } else {
                    val proxyPort = socksProxyService.getLocalPort()
                    log.info { "Creating OkHttp client with SOCKS5 proxy on 127.0.0.1:$proxyPort" }
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
                    OkHttpClient
                        .Builder()
                        .proxy(proxy)
                        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build()
                }

            cachedClient = client
            return client
        }
    }

    override fun close() {
        cachedClient?.let { client ->
            log.info { "Shutting down cached OkHttp client" }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            cachedClient = null
        }
    }
}
