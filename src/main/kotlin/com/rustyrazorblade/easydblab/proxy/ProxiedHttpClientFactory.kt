package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
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
     * Cluster traffic routes through the SOCKS tunnel when one is active (see [Socks5ProxySelector]);
     * otherwise it connects directly. Implementations should return a cached singleton client.
     *
     * @return Configured OkHttpClient ready for HTTP requests
     */
    fun createClient(): OkHttpClient
}

/**
 * HTTP client factory that creates OkHttp clients for cluster traffic.
 *
 * The SOCKS proxy is configured **explicitly** on the client via [Socks5ProxySelector], which reads
 * the published proxy port ([Constants.Proxy.PORT_PROPERTY]). We do NOT rely on the global
 * `socksProxyHost` property — that would route every socket in the JVM (including the AWS SDK)
 * through the tunnel. Scoping the proxy to this client keeps AWS and other traffic direct.
 *
 * When no proxy is running (Tailscale active, or before the proxy starts), the selector returns
 * `NO_PROXY` and these clients connect directly.
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

            log.info { "Creating OkHttp client (cluster traffic routed via SOCKS tunnel when active)" }
            val client =
                OkHttpClient
                    .Builder()
                    .proxySelector(Socks5ProxySelector())
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
