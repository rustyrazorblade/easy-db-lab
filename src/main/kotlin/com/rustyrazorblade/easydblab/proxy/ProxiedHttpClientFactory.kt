package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Factory interface for creating HTTP clients.
 *
 * Abstracts the client creation to allow different configurations.
 * Implementations should cache and reuse clients. Call [close] to release
 * background threads (HTTP/2 readers, connection pool) when done.
 */
interface HttpClientFactory : AutoCloseable {
    /**
     * Get an OkHttp client configured for cluster access.
     * When the SOCKS proxy is active, JVM system properties route traffic automatically
     * via [java.net.ProxySelector]. Implementations should return a cached singleton client.
     *
     * @return Configured OkHttpClient ready for HTTP requests
     */
    fun createClient(): OkHttpClient
}

/**
 * HTTP client factory that creates OkHttp clients for cluster traffic.
 *
 * When the SOCKS5 proxy is active, JVM system properties (`socksProxyHost`, `socksProxyPort`)
 * are set by [ProcessSocksProxyService] so OkHttp routes through the proxy automatically via
 * [java.net.ProxySelector.getDefault]. No explicit proxy configuration is needed here.
 *
 * When Tailscale is active, private IPs are directly reachable and no proxy is started,
 * so these clients connect directly.
 */
class ProxiedHttpClientFactory : HttpClientFactory {
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

            log.info { "Creating OkHttp client (proxy routing via JVM system properties if active)" }
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()

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
