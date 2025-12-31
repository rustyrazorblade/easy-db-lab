package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.session.ProgrammaticArguments
import com.datastax.oss.driver.internal.core.context.DefaultDriverContext
import com.datastax.oss.driver.internal.core.context.NettyOptions
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Custom driver context that injects SOCKS proxy support into the Netty layer.
 *
 * This context overrides [buildNettyOptions] to return a [SocksProxyNettyOptions]
 * instance that routes all Cassandra connections through a SOCKS5 proxy.
 *
 * Usage:
 * ```
 * val context = SocksProxyDriverContext(configLoader, programmaticArguments, proxyHost, proxyPort)
 * ```
 */
class SocksProxyDriverContext(
    configLoader: DriverConfigLoader,
    programmaticArguments: ProgrammaticArguments,
    private val proxyHost: String,
    private val proxyPort: Int,
) : DefaultDriverContext(configLoader, programmaticArguments) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun buildNettyOptions(): NettyOptions {
        log.debug { "Building SocksProxyNettyOptions with proxy $proxyHost:$proxyPort" }
        return SocksProxyNettyOptions(this, proxyHost, proxyPort)
    }
}
