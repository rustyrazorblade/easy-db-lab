package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.environment.NetworkMode
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Factory interface for creating Kubernetes clients.
 *
 * Abstracts client creation to allow different configurations
 * (direct connection, SOCKS proxy, etc.)
 */
interface KubernetesClientFactory {
    /**
     * Create a Kubernetes client for the cluster named by [api].
     *
     * The kubeconfig is loaded by file path with an explicit context, so relative certificate and
     * exec-plugin paths resolve against the kubeconfig's directory. The JVM-global `kubeconfig`
     * property and `KUBECONFIG` environment variable are never read.
     *
     * @param api The kubeconfig path and the context to use
     * @return Configured KubernetesClient ready for API calls
     */
    fun createClient(api: KubeApi): KubernetesClient
}

/**
 * Kubernetes client factory that routes K8s API traffic through the SOCKS5 proxy when active.
 *
 * The proxy port is read from the [Constants.Proxy.PORT_PROPERTY] system property (set by
 * [com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService] when the proxy starts).
 * When the property is absent (Tailscale active, no proxy started), the client connects directly.
 *
 * The SOCKS proxy is configured explicitly via `Config.httpsProxy`. We rely on this rather than the
 * global `socksProxyHost` precisely so that only K8s traffic uses the tunnel — AWS and other traffic
 * stays direct.
 *
 * The proxy is set only in [NetworkMode.Tunnel]. In [NetworkMode.Direct] (K3s in Docker, a
 * kubeconfig cluster) the API is reachable from this machine, so a port another command left
 * published (in the REPL or the server) never sends it through SOCKS.
 *
 * @param networkMode how this machine reaches the cluster now, asked for every client
 */
class ProxiedKubernetesClientFactory(
    private val networkMode: () -> NetworkMode = { NetworkMode.Tunnel },
) : KubernetesClientFactory {
    override fun createClient(api: KubeApi): KubernetesClient {
        val config = api.loadConfig()

        val mode = networkMode()
        if (mode == NetworkMode.Tunnel) {
            System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()?.let { port ->
                config.httpsProxy = socksProxyUrl(port)
            }
        }
        log.debug { "Kubernetes client in network mode $mode, proxy ${config.httpsProxy ?: "none"}" }
        config.applyClusterClientTimeouts()

        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }
}

private val log = KotlinLogging.logger {}

private const val CONNECTION_TIMEOUT_MS = 30000
private const val REQUEST_TIMEOUT_MS = 60000

/**
 * Sets the timeouts every client of the cluster's Kubernetes API uses.  They are long enough for a
 * request that goes through the SOCKS tunnel.
 */
fun Config.applyClusterClientTimeouts() {
    connectionTimeout = CONNECTION_TIMEOUT_MS
    requestTimeout = REQUEST_TIMEOUT_MS
}

/** The per-client `httpsProxy` value for the SOCKS tunnel listening on [port]. */
fun socksProxyUrl(port: Int): String = "socks5://${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:$port"
