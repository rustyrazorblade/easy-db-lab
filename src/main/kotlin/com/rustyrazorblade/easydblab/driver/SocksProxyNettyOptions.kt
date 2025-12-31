package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.internal.core.context.DefaultNettyOptions
import com.datastax.oss.driver.internal.core.context.InternalDriverContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.handler.proxy.Socks5ProxyHandler
import java.net.InetSocketAddress

/**
 * Custom NettyOptions that routes all Cassandra connections through a SOCKS5 proxy.
 *
 * This class extends [DefaultNettyOptions] and adds a [Socks5ProxyHandler] to the
 * Netty channel pipeline, enabling transparent proxying of all driver connections.
 *
 * @param context The internal driver context
 * @param proxyHost The SOCKS5 proxy host (e.g., "127.0.0.1")
 * @param proxyPort The SOCKS5 proxy port (e.g., 1080)
 */
class SocksProxyNettyOptions(
    context: InternalDriverContext,
    private val proxyHost: String,
    private val proxyPort: Int,
) : DefaultNettyOptions(context) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    init {
        log.info { "Initialized SocksProxyNettyOptions with proxy $proxyHost:$proxyPort" }
    }

    override fun afterBootstrapInitialized(bootstrap: Bootstrap) {
        // Call parent to set socket options (TCP_NODELAY, SO_KEEPALIVE, etc.)
        super.afterBootstrapInitialized(bootstrap)

        log.info { "Configuring SOCKS5 proxy handler for $proxyHost:$proxyPort" }

        // Store the original handler set by the driver
        val originalHandler = bootstrap.config().handler()
        log.debug { "Original handler type: ${originalHandler?.javaClass?.name}" }

        // Replace with our handler that adds SOCKS5 proxy support
        bootstrap.handler(
            object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    val pipeline = ch.pipeline()
                    log.info { "Initializing channel with SOCKS5 proxy to $proxyHost:$proxyPort" }

                    // First, invoke the original handler to set up the driver's pipeline
                    if (originalHandler is ChannelInitializer<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val typedHandler = originalHandler as ChannelInitializer<Channel>
                        try {
                            // Use reflection to call the protected initChannel method
                            val initMethod =
                                ChannelInitializer::class.java.getDeclaredMethod(
                                    "initChannel",
                                    Channel::class.java,
                                )
                            initMethod.isAccessible = true
                            initMethod.invoke(typedHandler, ch)
                            log.debug { "Original handler initialized successfully" }
                        } catch (e: Exception) {
                            log.error(e) { "Failed to initialize original channel handler" }
                            throw e
                        }
                    } else {
                        log.warn { "Original handler is not a ChannelInitializer: ${originalHandler?.javaClass?.name}" }
                    }

                    log.debug { "Pipeline before SOCKS5: ${pipeline.names()}" }

                    // Now add the SOCKS5 proxy handler at the very front of the pipeline
                    // This must be added AFTER the original pipeline is set up, so it
                    // intercepts connections before any other handlers process them
                    val proxyHandler = Socks5ProxyHandler(InetSocketAddress(proxyHost, proxyPort))
                    proxyHandler.setConnectTimeoutMillis(30000L)
                    pipeline.addFirst("socks5-proxy", proxyHandler)

                    log.info { "Added SOCKS5 proxy handler to channel pipeline" }
                    log.debug { "Pipeline after SOCKS5: ${pipeline.names()}" }
                }
            },
        )
    }
}
