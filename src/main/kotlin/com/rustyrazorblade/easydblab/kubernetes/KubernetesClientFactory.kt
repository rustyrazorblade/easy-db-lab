package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder

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
 */
class ProxiedKubernetesClientFactory : KubernetesClientFactory {
    override fun createClient(api: KubeApi): KubernetesClient {
        val config = api.loadConfig()

        System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()?.let { port ->
            config.httpsProxy = socksProxyUrl(port)
        }
        config.applyClusterClientTimeouts()

        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }
}

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
