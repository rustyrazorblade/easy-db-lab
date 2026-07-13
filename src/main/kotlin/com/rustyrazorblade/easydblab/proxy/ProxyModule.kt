package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.services.ResourceManager
import org.koin.dsl.module

/** Socket timeout (ms) for a single end-to-end tunnel reachability probe. */
private const val PROBE_CONNECT_TIMEOUT_MS = 1000

/**
 * Koin module for proxy-related dependency injection.
 *
 * Provides:
 * - [TunnelReachabilityProbe] as a singleton — the real SOCKS-based end-to-end tunnel check
 * - [SocksProxyService] as a singleton — manages the detached SSH proxy process
 * - [HttpClientFactory] for creating OkHttp clients (proxy routing via JVM system properties)
 * - [ProxyAvailability] as a singleton — lets a `@RequiresProxy(tolerateFailure = true)`
 *   command (currently only `Status`) observe a proxy establishment failure the executor
 *   chose not to propagate
 */
val proxyModule =
    module {
        // End-to-end tunnel reachability probe — the injectable seam the proxy service uses to
        // decide whether a freshly started tunnel actually carries traffic.
        single<TunnelReachabilityProbe> { SocksTunnelReachabilityProbe(PROBE_CONNECT_TIMEOUT_MS) }

        // SOCKS proxy service - singleton to share state across requests.
        // Uses ProcessSocksProxyService which launches a detached OS process that
        // persists across JVM restarts and is reused via .socks5-proxy-state.
        single<SocksProxyService> { ProcessSocksProxyService(get(), get()) }

        // Proxy availability holder - singleton so DefaultCommandExecutor and the command it
        // executes share the same instance within a process.
        single<ProxyAvailability> { DefaultProxyAvailability() }

        // HTTP client factory — registered with ResourceManager so the cached OkHttpClient
        // is cleaned up on exit.
        single<HttpClientFactory> {
            ProxiedHttpClientFactory().also { factory ->
                get<ResourceManager>().register(factory)
            }
        }
    }
