package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.nio.file.Path

/**
 * Factory interface for creating Kubernetes clients.
 *
 * Abstracts client creation to allow different configurations
 * (direct connection, SOCKS proxy, etc.)
 */
interface KubernetesClientFactory {
    /**
     * Create a Kubernetes client from a kubeconfig file.
     *
     * @param kubeconfigPath Path to the kubeconfig file
     * @return Configured KubernetesClient ready for API calls
     */
    fun createClient(kubeconfigPath: Path): KubernetesClient
}

/**
 * Kubernetes client factory that routes K8s API traffic through the SOCKS5 proxy when active.
 *
 * The proxy port is read from the JVM system property `socksProxyPort` (set by
 * [com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService] when the proxy starts).
 * When the property is absent (Tailscale active, no proxy started), the client connects directly.
 *
 * fabric8 uses OkHttp and may not automatically pick up Java's [java.net.ProxySelector],
 * so the SOCKS proxy is configured explicitly via `Config.httpsProxy` when the port is available.
 */
class ProxiedKubernetesClientFactory : KubernetesClientFactory {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 30000
        private const val REQUEST_TIMEOUT_MS = 60000
    }

    override fun createClient(kubeconfigPath: Path): KubernetesClient {
        val kubeconfigContent = kubeconfigPath.toFile().readText()
        val config = Config.fromKubeconfig(kubeconfigContent)

        System.getProperty("socksProxyPort")?.toIntOrNull()?.let { port ->
            config.httpsProxy = "socks5://127.0.0.1:$port"
        }

        config.connectionTimeout = CONNECTION_TIMEOUT_MS
        config.requestTimeout = REQUEST_TIMEOUT_MS

        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }
}
