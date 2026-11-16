package com.rustyrazorblade.easydblab.kubernetes

import org.koin.dsl.module

/**
 * Koin module for Kubernetes-related dependency injection.
 *
 * Provides [KubernetesClientFactory]: creates K8s API clients. When the SOCKS5 proxy is active,
 * the proxy port is read from the private `Constants.Proxy.PORT_PROPERTY` system property and the
 * SOCKS proxy is configured explicitly (the global `socksProxyHost` is not set, keeping AWS direct).
 *
 * The cluster operations themselves are [com.rustyrazorblade.easydblab.services.K8sService], bound
 * to the workspace by [com.rustyrazorblade.easydblab.services.K8sClientProvider].
 */
val kubernetesModule =
    module {
        factory<KubernetesClientFactory> {
            ProxiedKubernetesClientFactory()
        }
    }
