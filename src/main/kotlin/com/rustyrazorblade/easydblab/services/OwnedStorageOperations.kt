package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScopeSource

/**
 * Marks every ConfigMap the CLI creates (`cluster-config`, `grafana-datasources`, the stress sidecar
 * config, the metrics registry entries) as easy-db-lab's and as the current cluster's, so a sweep by
 * the cluster label finds them (design D6). The ownership labels are merged over the caller's, so an
 * object can never claim another cluster's id. The scope is read on each create.
 */
class OwnedStorageOperations(
    private val delegate: K8sStorageOperations,
    private val scopeSource: InstallScopeSource,
) : K8sStorageOperations by delegate {
    override fun createConfigMap(
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String>,
    ): Result<Unit> = delegate.createConfigMap(namespace, name, data, labels + scopeSource.current().labels)
}
