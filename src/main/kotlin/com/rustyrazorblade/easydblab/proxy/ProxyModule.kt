package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.services.ResourceManager
import org.koin.dsl.module

/**
 * Koin module for proxy-related dependency injection.
 *
 * Provides:
 * - [SocksProxyService] as a singleton — manages the detached SSH proxy process
 * - [HttpClientFactory] for creating OkHttp clients (proxy routing via JVM system properties)
 */
val proxyModule =
    module {
        // SOCKS proxy service - singleton to share state across requests.
        // Uses ProcessSocksProxyService which launches a detached OS process that
        // persists across JVM restarts and is reused via .socks5-proxy-state.
        single<SocksProxyService> { ProcessSocksProxyService(get()) }

        // HTTP client factory — registered with ResourceManager so the cached OkHttpClient
        // is cleaned up on exit.
        single<HttpClientFactory> {
            ProxiedHttpClientFactory().also { factory ->
                get<ResourceManager>().register(factory)
            }
        }
    }
