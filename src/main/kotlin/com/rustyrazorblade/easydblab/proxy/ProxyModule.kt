package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
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
 * - [ProxyEnvFile] as a singleton — the workspace's proxy state for shell-side tools
 * - [ToolWrapperInstaller] as a singleton — writes the shell-side tool wrappers
 * - [WorkspaceShellTools] as a singleton — the wrappers plus the Tailscale flag, at `up` and restore
 * - [ProxyPreflight] as a singleton — starts the tunnel for `@RequiresProxy` commands
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
        single<SocksProxyService> {
            ProcessSocksProxyService(get(), get(), verifyAttempts = get<SshRoute>().tunnelVerifyAttempts, envFile = get())
        }

        // The workspace's sourceable proxy state for the shell-side tool wrappers and env.sh.
        single { ProxyEnvFile(get<Context>().workingDirectory) }

        // Writes the kubectl/helm/cilium/curl/skopeo/k9s wrappers into a workspace's bin/.
        single { ToolWrapperInstaller() }

        // Prepares a workspace for shell-side tools: the wrappers and the Tailscale flag.
        single { WorkspaceShellTools(get()) }

        // The executor's tunnel start for @RequiresProxy commands.
        single { ProxyPreflight(get(), get(), get()) }

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
