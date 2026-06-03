package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.nio.file.Path

/**
 * Factory interface for creating Kubernetes clients.
 *
 * Abstracts the client creation to allow different configurations
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
 * Kubernetes client factory that routes K8s API traffic through a SOCKS5 proxy or directly,
 * depending on whether Tailscale was active at init time.
 *
 * @property proxyHost The SOCKS5 proxy host (typically "127.0.0.1")
 * @property socksProxyService Injected so getLocalPort() is called at createClient() time, not at construction
 * @property tailscaleActive Whether Tailscale was active at init time; skips proxy when true
 */
class ProxiedKubernetesClientFactory(
    private val proxyHost: String = "127.0.0.1",
    private val socksProxyService: SocksProxyService,
    private val tailscaleActive: Boolean,
) : KubernetesClientFactory {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 30000
        private const val REQUEST_TIMEOUT_MS = 60000
    }

    override fun createClient(kubeconfigPath: Path): KubernetesClient {
        val kubeconfigContent = kubeconfigPath.toFile().readText()
        val config = Config.fromKubeconfig(kubeconfigContent)

        if (!tailscaleActive) {
            config.httpsProxy = "socks5://$proxyHost:${socksProxyService.getLocalPort()}"
        }

        config.connectionTimeout = CONNECTION_TIMEOUT_MS
        config.requestTimeout = REQUEST_TIMEOUT_MS

        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }
}
