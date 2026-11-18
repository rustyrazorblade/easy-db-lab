package com.rustyrazorblade.easydblab.services

import org.koin.core.Koin

/**
 * The real stage manifests from the manifest builders a test registered in [koin], with the core
 * collector reading the workload scrape configs through [k8sClientProvider].
 */
fun realStageManifests(
    koin: Koin,
    k8sClientProvider: K8sClientProvider,
): ObservabilityStageManifests =
    ObservabilityStageManifests(
        CoreCollectorManifests(k8sClientProvider, koin.get()),
        CollectorAgentManifests(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()),
        BackendManifests(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()),
    )
