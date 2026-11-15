package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients for K8s API access.
 *
 * The client is reached only through the cluster's [com.rustyrazorblade.easydblab.kubernetes.KubeApi]
 * (kubeconfig path and pinned context) resolved by the injected [KubeApiLocator]; the control node's
 * identity plays no part.
 *
 * When the SOCKS5 proxy is active (started eagerly by [DefaultCommandExecutor] before any command
 * runs), the proxy port is published via the private
 * [com.rustyrazorblade.easydblab.Constants.Proxy.PORT_PROPERTY] and the factory uses it to configure
 * the per-client HTTPS proxy. The global `socksProxyHost` is intentionally not set, so only cluster
 * traffic tunnels — AWS and other traffic stay direct. When Tailscale is active, the proxy is not
 * started (no port published) and the client connects directly.
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
