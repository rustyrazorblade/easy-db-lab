package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients for K8s API access.
 *
 * When the SOCKS5 proxy is active (started eagerly by [com.rustyrazorblade.easydblab.services.DefaultCommandExecutor]
 * before any command runs), the proxy port is published via the private
 * [com.rustyrazorblade.easydblab.Constants.Proxy.PORT_PROPERTY] and [ProxiedKubernetesClientFactory]
 * uses it to configure the HTTPS proxy. The global `socksProxyHost` is intentionally not set, so only
 * cluster traffic tunnels — AWS and other traffic stay direct.
 *
 * When Tailscale is active, the proxy is not started (no port published) and the client connects
 * directly to the private IP without any proxy.
 */
class K8sClientProvider(
    private val clusterStateManager: ClusterStateManager,
) {
    fun createClient(controlHost: ClusterHost): KubernetesClient {
        log.debug { "Creating K8s client for ${controlHost.alias} (${controlHost.privateIp})" }

        val kubeconfigPath = Path.of(Constants.K3s.LOCAL_KUBECONFIG)
        log.debug { "Using kubeconfig=$kubeconfigPath tailscale=${clusterStateManager.load().isTailscaleEnabled()}" }

        val clientFactory = ProxiedKubernetesClientFactory()
        val client = clientFactory.createClient(kubeconfigPath)
        log.debug { "K8s client created, master URL: ${client.configuration.masterUrl}" }

        return client
    }
}
