package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.koin.dsl.module
import java.nio.file.Paths

/**
 * Koin module for Kubernetes-related dependency injection.
 *
 * Provides:
 * - KubernetesClientFactory: Creates K8s API clients, direct or SOCKS-proxied based on tailscaleActive
 * - KubernetesService: High-level service for K8s operations
 *
 * Note: Requires SocksProxyService from proxyModule and expects kubeconfig
 * to be downloaded to the local filesystem.
 */
val kubernetesModule =
    module {
        factory<KubernetesClientFactory> {
            val tailscaleActive = get<ClusterStateManager>().load().tailscaleActive
            ProxiedKubernetesClientFactory(
                proxyHost = "127.0.0.1",
                proxyPort = get<SocksProxyService>().getLocalPort(),
                tailscaleActive = tailscaleActive,
            )
        }

        // Kubernetes service - factory because it holds state (API client) tied to proxy session
        // The kubeconfigPath is resolved at runtime when the service is requested
        factory<KubernetesService> { (kubeconfigPath: String) ->
            DefaultKubernetesService(
                clientFactory = get(),
                kubeconfigPath = Paths.get(kubeconfigPath),
            )
        }
    }

/**
 * Local kubeconfig path based on Constants.K3s configuration.
 * The kubeconfig is downloaded from the control node during cluster setup.
 */
fun getLocalKubeconfigPath(profileDir: String): String = "$profileDir/${Constants.K3s.LOCAL_KUBECONFIG}"
