package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Factory for creating CqlSession instances.
 *
 * [createSession] routes through a SOCKS5 proxy; [createDirectSession] connects
 * directly (used when Tailscale is active and private IPs are reachable).
 */
interface CqlSessionFactory {
    /**
     * Create a CqlSession connected to the specified hosts through the SOCKS proxy.
     *
     * @param contactPoints List of Cassandra host private IPs
     * @param datacenter The local datacenter name
     * @param proxyPort The local SOCKS5 proxy port
     * @return A connected CqlSession
     */
    fun createSession(
        contactPoints: List<String>,
        datacenter: String,
        proxyPort: Int,
    ): CqlSession

    /**
     * Create a CqlSession connected directly to the specified hosts (no proxy).
     * Used when Tailscale is active and private IPs are reachable from the local machine.
     *
     * @param contactPoints List of Cassandra host private IPs
     * @param datacenter The local datacenter name
     * @return A connected CqlSession
     */
    fun createDirectSession(
        contactPoints: List<String>,
        datacenter: String,
    ): CqlSession
}

/**
 * Default implementation using a custom driver context with SOCKS proxy support.
 *
 * This implementation uses [SocksProxySessionBuilder] which injects a custom
 * [SocksProxyDriverContext] that provides [SocksProxyNettyOptions] for routing
 * all connections through a SOCKS5 proxy.
 */
open class DefaultCqlSessionFactory : CqlSessionFactory {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val DEFAULT_PORT = 9042
        private const val DEFAULT_PROXY_HOST = "127.0.0.1"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }

    internal open fun newDirectBuilder(): CqlSessionBuilder = CqlSession.builder()

    internal open fun newProxiedBuilder(
        proxyHost: String,
        proxyPort: Int,
    ): SocksProxySessionBuilder = SocksProxySessionBuilder(proxyHost, proxyPort)

    override fun createSession(
        contactPoints: List<String>,
        datacenter: String,
        proxyPort: Int,
    ): CqlSession {
        log.info { "Creating CqlSession with SOCKS proxy on port $proxyPort" }
        log.debug { "Contact points: $contactPoints, datacenter: $datacenter" }

        val builder =
            newProxiedBuilder(DEFAULT_PROXY_HOST, proxyPort)
                .withConfigLoader(buildConfigLoader(datacenter))

        contactPoints.forEach { host ->
            builder.addContactPoint(InetSocketAddress(host, DEFAULT_PORT))
        }

        val session = builder.build()
        log.info { "CqlSession created successfully" }
        return session
    }

    override fun createDirectSession(
        contactPoints: List<String>,
        datacenter: String,
    ): CqlSession {
        log.info { "Creating CqlSession with direct connection (Tailscale active)" }
        log.debug { "Contact points: $contactPoints, datacenter: $datacenter" }

        val builder = newDirectBuilder().withConfigLoader(buildConfigLoader(datacenter))

        contactPoints.forEach { host ->
            builder.addContactPoint(InetSocketAddress(host, DEFAULT_PORT))
        }

        val session = builder.build()
        log.info { "CqlSession created successfully (direct)" }
        return session
    }

    private fun buildConfigLoader(datacenter: String) =
        DriverConfigLoader
            .programmaticBuilder()
            .withDuration(
                DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT,
                Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS),
            ).withDuration(
                DefaultDriverOption.REQUEST_TIMEOUT,
                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
            ).withString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER, datacenter)
            // Disable metadata to avoid additional connections during initial setup
            .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
            // Reduce connection pool size for initial connection
            .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 1)
            .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 1)
            .build()
}
