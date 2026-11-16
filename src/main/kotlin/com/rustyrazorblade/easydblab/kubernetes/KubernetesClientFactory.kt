package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.reason
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.io.IOException
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
 * The proxy port is read from the [Constants.Proxy.PORT_PROPERTY] system property (set by
 * [com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService] when the proxy starts).
 * When the property is absent (Tailscale active, no proxy started), the client connects directly.
 *
 * The SOCKS proxy is configured explicitly via `Config.httpsProxy`. We rely on this rather than the
 * global `socksProxyHost` precisely so that only K8s traffic uses the tunnel — AWS and other traffic
 * stays direct.
 */
class ProxiedKubernetesClientFactory : KubernetesClientFactory {
    /**
     * @throws IOException naming [kubeconfigPath] when the file cannot be read; the cause is kept, so a
     *   missing file is still recognised as a cluster that was never reached ([ClusterReach])
     * @throws IllegalStateException naming [kubeconfigPath] when its content is not a usable kubeconfig
     */
    override fun createClient(kubeconfigPath: Path): KubernetesClient {
        val kubeconfigContent =
            try {
                kubeconfigPath.toFile().readText()
            } catch (e: IOException) {
                throw IOException("Cannot read the kubeconfig at $kubeconfigPath: ${e.reason()}", e)
            }
        return try {
            val config = Config.fromKubeconfig(kubeconfigContent)

            System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()?.let { port ->
                config.httpsProxy = socksProxyUrl(port)
            }
            config.applyClusterClientTimeouts()

            KubernetesClientBuilder()
                .withConfig(config)
                .build()
        } catch (e: RuntimeException) {
            throw IllegalStateException("The kubeconfig at $kubeconfigPath is not usable: ${e.reason()}", e)
        }
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
