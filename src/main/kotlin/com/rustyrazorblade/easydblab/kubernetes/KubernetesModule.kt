package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import org.koin.dsl.module
import java.nio.file.Paths

/**
 * Koin module for Kubernetes-related dependency injection.
 *
 * Provides:
 * - [KubernetesClientFactory]: Creates K8s API clients. When the SOCKS5 proxy is active,
 *   the proxy port is read from the JVM system property `socksProxyPort`.
 * - [KubernetesService]: High-level service for K8s operations.
 */
val kubernetesModule =
    module {
        factory<KubernetesClientFactory> {
            ProxiedKubernetesClientFactory()
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
