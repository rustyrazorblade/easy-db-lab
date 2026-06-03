package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients, routing through a SOCKS5 proxy or directly
 * depending on whether Tailscale was active at init time.
 * Shared by all K8s operation implementations.
 */
class K8sClientProvider(
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
) {
    fun createClient(controlHost: ClusterHost): KubernetesClient {
        log.debug { "Creating K8s client for ${controlHost.alias} (${controlHost.privateIp})" }

        val tailscaleActive = clusterStateManager.load().tailscaleActive
        if (!tailscaleActive) {
            socksProxyService.ensureRunning(controlHost)
        }

        val kubeconfigPath = Path.of(Constants.K3s.LOCAL_KUBECONFIG)
        log.debug { "Using kubeconfig=$kubeconfigPath tailscale=$tailscaleActive" }

        val clientFactory =
            ProxiedKubernetesClientFactory(
                proxyHost = "127.0.0.1",
                socksProxyService = socksProxyService,
                tailscaleActive = tailscaleActive,
            )

        val client = clientFactory.createClient(kubeconfigPath)
        log.debug { "K8s client created, master URL: ${client.configuration.masterUrl}" }

        return client
    }
}
