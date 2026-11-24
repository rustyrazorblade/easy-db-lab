package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.store.StoreBackend
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope

/**
 * The contents of the runtime `cluster-config` ConfigMap: the object store in neutral `store_*`
 * keys ([StoreBackend]: bucket, prefix, region and endpoint) and the Tempo, Mimir and Loki prefixes
 * in it (only when the cluster has an account bucket), the cluster name (metric label), the tenant every in-cluster producer and backend sends in
 * `X-Scope-OrgID`, the namespace the cluster installs into (so a kit script or pod reads it from
 * one place), the present node capabilities (serial names in enum order, comma-joined, so an
 * unchanged set never changes the hash; the account-level `aws-account` describes no node and is
 * left out). It names no node address: every in-cluster caller reaches
 * the backends by Service DNS, so a cluster with no control host in its state renders it the same way.
 *
 * The collectors and Tempo read it, so their config hashes
 * ([com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator]) cover it. Every path that
 * applies one of those workloads — the full stack deploy and the collector resync on a kit
 * start/stop — computes the contents here, so both stamp the same hash for the same cluster and
 * neither rolls a workload whose configuration did not change.
 */
object ClusterConfigData {
    /** The ConfigMap's name. */
    const val NAME = "cluster-config"

    /**
     * The ConfigMap's data for [clusterState].
     *
     * @param defaultRegion the region used when the cluster state records none.
     */
    fun of(
        clusterState: ClusterState,
        defaultRegion: String,
    ): Map<String, String> =
        buildMap {
            StoreBackend.ofOrNull(clusterState, defaultRegion)?.let { putAll(storeKeys(it, clusterState)) }
            put("cluster_name", clusterState.clusterLabelName())
            put("tenant", clusterState.tenant())
            put("namespace", InstallScope.of(clusterState, ClusterContract.of(clusterState)).namespace.value)
            put(
                Constants.Capabilities.CLUSTER_CONFIG_KEY,
                NodeCapabilities
                    .from(clusterState)
                    .present()
                    .filter { it.describesNode }
                    .joinToString(Constants.Capabilities.CLUSTER_CONFIG_SEPARATOR) { it.serialName },
            )
        }

    /**
     * The `store_*` keys and the backend prefixes in the store. A cluster with no account bucket (a
     * local or brought one, #1061) publishes none: only the backends that write to the store read
     * them, and they fail through [StoreBackend.from] before they are applied.
     */
    private fun storeKeys(
        backend: StoreBackend,
        clusterState: ClusterState,
    ): Map<String, String> {
        val store = ObservabilityStore(backend.bucket, clusterState.tenant(), backend.prefix)
        return mapOf(
            "store_bucket" to backend.bucket,
            "store_prefix" to backend.prefix,
            "traces_store_prefix" to store.tracesPrefix(),
            "metrics_store_prefix" to store.metricsPrefix(),
            "logs_store_prefix" to store.logsPrefix(),
        ) + locationOf(backend)
    }

    /** Where the store is reached: the keys only the S3 fragments read. */
    private fun locationOf(backend: StoreBackend): Map<String, String> =
        when (backend) {
            is S3Backend -> mapOf("store_region" to backend.region, "store_endpoint" to backend.endpoint)
        }
}
