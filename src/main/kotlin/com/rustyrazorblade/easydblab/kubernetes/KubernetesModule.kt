package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import org.koin.dsl.module

/**
 * Koin module for Kubernetes-related dependency injection.
 *
 * Provides:
 * - [KubernetesClientFactory]: Creates K8s API clients. When the SOCKS5 proxy is active,
 *   the proxy port is read from the private `Constants.Proxy.PORT_PROPERTY` system property and the
 *   SOCKS proxy is configured explicitly (the global `socksProxyHost` is not set, keeping AWS direct).
 * - [KubernetesService]: High-level service for K8s operations, reaching the cluster named by the
 *   injected [KubeApiLocator] (the workspace kubeconfig and the context pinned in cluster state).
 */
val kubernetesModule =
    module {
        factory<KubernetesClientFactory> {
            ProxiedKubernetesClientFactory()
        }

        // Holds no client: every operation asks the locator for the kubeconfig and context, builds a
        // client with the current proxy port, and closes it, so a long-lived instance never goes stale.
        factory<KubernetesService> { DefaultKubernetesService(clientFactory = get(), locator = get()) }
    }

/**
 * Local kubeconfig path based on Constants.K3s configuration.
 * The kubeconfig is downloaded from the control node during cluster setup.
 */
fun getLocalKubeconfigPath(profileDir: String): String = "$profileDir/${Constants.K3s.LOCAL_KUBECONFIG}"
