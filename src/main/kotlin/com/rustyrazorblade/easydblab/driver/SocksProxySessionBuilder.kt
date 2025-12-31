package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.session.ProgrammaticArguments
import com.datastax.oss.driver.api.core.session.SessionBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Custom session builder that creates CqlSessions with SOCKS5 proxy support.
 *
 * This builder extends the standard [SessionBuilder] and overrides [buildContext]
 * to inject a [SocksProxyDriverContext], which in turn provides custom NettyOptions
 * that route all connections through a SOCKS5 proxy.
 *
 * Usage:
 * ```
 * val session = SocksProxySessionBuilder("127.0.0.1", 1080)
 *     .addContactPoint(InetSocketAddress("cassandra-host", 9042))
 *     .withLocalDatacenter("datacenter1")
 *     .build()
 * ```
 *
 * @param proxyHost The SOCKS5 proxy host
 * @param proxyPort The SOCKS5 proxy port
 */
class SocksProxySessionBuilder(
    private val proxyHost: String,
    private val proxyPort: Int,
) : SessionBuilder<SocksProxySessionBuilder, CqlSession>() {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    init {
        log.info { "Creating SocksProxySessionBuilder with proxy $proxyHost:$proxyPort" }
    }

    override fun buildContext(
        configLoader: DriverConfigLoader,
        programmaticArguments: ProgrammaticArguments,
    ): DriverContext {
        log.debug { "Building SocksProxyDriverContext" }
        return SocksProxyDriverContext(
            configLoader,
            programmaticArguments,
            proxyHost,
            proxyPort,
        )
    }

    override fun wrap(session: CqlSession): CqlSession = session
}
