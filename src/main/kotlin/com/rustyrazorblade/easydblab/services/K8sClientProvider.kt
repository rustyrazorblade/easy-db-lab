package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Creates Kubernetes clients using a SOCKS5 proxy for reliable internal network access.
 * Shared by all K8s operation implementations.
 */
class K8sClientProvider(
    private val socksProxyService: SocksProxyService,
) {
    fun createClient(controlHost: ClusterHost): KubernetesClient {
        log.info { "Creating K8s client for ${controlHost.alias} (${controlHost.privateIp})" }

        socksProxyService.ensureRunning(controlHost)
        val proxyPort = socksProxyService.getLocalPort()

        val kubeconfigPath = Path.of(Constants.K3s.LOCAL_KUBECONFIG)
        log.info { "Using kubeconfig from $kubeconfigPath" }
        log.info { "Using proxied Kubernetes client on localhost:$proxyPort" }

        val clientFactory =
            ProxiedKubernetesClientFactory(
                proxyHost = "localhost",
                proxyPort = proxyPort,
            )

        val client = clientFactory.createClient(kubeconfigPath)
        log.info { "K8s client created, master URL: ${client.configuration.masterUrl}" }

        return client
    }
}
