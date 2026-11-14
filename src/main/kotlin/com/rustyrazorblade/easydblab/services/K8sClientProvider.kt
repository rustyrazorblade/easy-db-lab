package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients for the cluster of the current workspace.
 *
 * The kubeconfig is the workspace's, resolved by [WorkspaceKubeconfig] against
 * [Context.workingDirectory], so the client reaches the workspace's cluster whatever directory the
 * process started in.
 *
 * When the SOCKS5 proxy is active (started eagerly by [com.rustyrazorblade.easydblab.services.DefaultCommandExecutor]
 * before any command runs), the proxy port is published via the private
 * [com.rustyrazorblade.easydblab.Constants.Proxy.PORT_PROPERTY] and the production
 * [com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory] uses it to configure the
 * HTTPS proxy for this client only. The global `socksProxyHost` is intentionally not set, so only
 * cluster traffic tunnels, and AWS and other traffic stay direct.
 *
 * When Tailscale is active, the proxy is not started (no port published) and the client connects
 * directly to the private IP without any proxy.
 */
class K8sClientProvider(
    private val context: Context,
    private val clientFactory: KubernetesClientFactory,
) {
    fun createClient(): KubernetesClient {
        val kubeconfigFile = WorkspaceKubeconfig.require(context.workingDirectory)
        log.debug { "Creating K8s client from kubeconfig=$kubeconfigFile" }
        val client = clientFactory.createClient(kubeconfigFile.toPath())
        log.debug { "K8s client created, master URL: ${client.configuration.masterUrl}" }
        return client
    }
}
