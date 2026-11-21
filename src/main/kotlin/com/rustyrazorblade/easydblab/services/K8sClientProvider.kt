package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients for the cluster of the current workspace.
 *
 * The client is reached only through the cluster's [com.rustyrazorblade.easydblab.kubernetes.KubeApi]
 * (kubeconfig path and pinned context) resolved by the injected [KubeApiLocator] for every client, so
 * the client reaches the workspace's cluster whatever directory the process started in, and the
 * control node's identity plays no part.
 *
 * When the SOCKS5 proxy is active (started eagerly by [DefaultCommandExecutor] before any command
 * runs), the proxy port is published via the private
 * [com.rustyrazorblade.easydblab.Constants.Proxy.PORT_PROPERTY] and the production
 * [com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory] uses it to configure the
 * HTTPS proxy for this client only. The global `socksProxyHost` is intentionally not set, so only
 * cluster traffic tunnels, and AWS and other traffic stay direct. When the network mode needs no
 * tunnel, the proxy is not started (no port published) and the client connects directly.
 */
class K8sClientProvider(
    private val locator: KubeApiLocator,
    private val factory: KubernetesClientFactory,
) {
    fun createClient(): KubernetesClient {
        val api = locator.locate()
        log.debug { "Creating K8s client for context=${api.context} kubeconfig=${api.kubeconfig}" }
        val client = factory.createClient(api)
        log.debug { "K8s client created, master URL: ${client.configuration.masterUrl}" }
        return client
    }
}
