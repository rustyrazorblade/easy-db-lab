package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Factory interface for the base HTTP client cluster clients derive from.
 *
 * The base client carries only shared settings (timeouts, the connection pool and dispatcher); it
 * has no proxy route. A caller reaching a cluster Service applies the route the service-access seam
 * gives it with `OkHttpClient.forEndpoint`, which derives a client with that endpoint's own proxy
 * (SOCKS on the tunnel's current port, or none). Implementations should cache and reuse the base
 * client. Call [close] to release background threads (HTTP/2 readers, connection pool) when done.
 */
interface HttpClientFactory : AutoCloseable {
    /**
     * Get the cached base OkHttp client: timeouts only, no proxy route. Apply a
     * `ServiceEndpoint` with `forEndpoint` before reaching a cluster Service.
     *
     * @return the shared base OkHttpClient
     */
    fun createClient(): OkHttpClient
}

/**
 * [HttpClientFactory] that caches one base OkHttp client for the process.
 *
 * The client has no proxy and no proxy selector: a route on a process-wide client would be shared
 * mutable state (a selector reading the published tunnel port goes stale when the tunnel moves), so
 * every cluster route is set per endpoint by `forEndpoint`. The global `socksProxyHost` property is
 * never set, so AWS and other traffic stays direct. The name records that its clients are the ones
 * cluster traffic is routed from.
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

            log.debug { "Creating base OkHttp client (no proxy; each endpoint sets its own route)" }
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
