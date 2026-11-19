package com.rustyrazorblade.easydblab.services

import org.koin.core.Koin

/**
 * The real stage manifests from the manifest builders a test registered in [koin], with the core
 * collector reading the workload scrape configs through [k8sClientProvider] and the account
 * compactor prepared by [compactorStage].
 */
fun realStageManifests(
    koin: Koin,
    k8sClientProvider: K8sClientProvider,
    compactorStage: CompactorStage,
): ObservabilityStageManifests =
    ObservabilityStageManifests(
        CoreCollectorManifests(k8sClientProvider, koin.get()),
        CollectorAgentManifests(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()),
        BackendManifests(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()),
        compactorStage,
    )
