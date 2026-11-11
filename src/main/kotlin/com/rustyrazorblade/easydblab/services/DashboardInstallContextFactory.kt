package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.documentsUrl

/**
 * Builds the [DashboardInstallContext] of the workspace's cluster, so the core tree, kit `start`
 * and `grafana install` apply the same install-time pass. The tenant list comes from the shared
 * store, the same listing that makes the Grafana datasources.
 */
class DashboardInstallContextFactory(
    private val tenantDirectory: TenantDirectory,
) {
    /** The install context of [clusterState]'s cluster, whose Grafana runs on [controlHost]. */
    fun forCluster(
        clusterState: ClusterState,
        controlHost: ClusterHost,
    ): DashboardInstallContext =
        DashboardInstallContext(
            cluster = clusterState.clusterLabelName(),
            tenants = tenantDirectory.list(ObservabilityStore.from(clusterState).bucket, clusterState.tenant()),
            documentsUrl = documentsUrl(controlHost),
        )
}
