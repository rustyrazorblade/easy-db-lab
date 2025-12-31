package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.koin.dsl.module
import java.nio.file.Paths

/**
 * Koin module for Kubernetes-related dependency injection.
 *
 * Provides:
 * - KubernetesClientFactory: Creates K8s API clients with SOCKS proxy support
 * - KubernetesService: High-level service for K8s operations
 *
 * Note: Requires SocksProxyService from proxyModule and expects kubeconfig
 * to be downloaded to the local filesystem.
 */
val kubernetesModule =
    module {
        // Kubernetes client factory - uses SOCKS proxy for private cluster access
        factory<KubernetesClientFactory> {
            val proxyService = get<SocksProxyService>()
            ProxiedKubernetesClientFactory(
                // Use 127.0.0.1 for consistency with SOCKS proxy binding
                proxyHost = "127.0.0.1",
                proxyPort = proxyService.getLocalPort(),
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
