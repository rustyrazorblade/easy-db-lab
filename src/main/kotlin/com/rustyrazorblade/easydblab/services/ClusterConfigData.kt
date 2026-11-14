package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.store.StoreBackend

/**
 * The contents of the runtime `cluster-config` ConfigMap: the control node IP (log/OTLP destination),
 * the object store in neutral `store_*` keys ([StoreBackend]: bucket, prefix, region and endpoint)
 * and the Tempo, Mimir and Loki prefixes in it, the cluster name (metric label), the tenant every
 * in-cluster producer and backend sends in `X-Scope-OrgID`, and the present node capabilities
 * (serial names in enum order, comma-joined, so an unchanged set never changes the hash).
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
     * The ConfigMap's data for [clusterState] with [controlNode] as its control node.
     *
     * @param defaultRegion the region used when the cluster state records none.
     */
    fun of(
        controlNode: ClusterHost,
        clusterState: ClusterState,
        defaultRegion: String,
    ): Map<String, String> {
        val backend = StoreBackend.from(clusterState, defaultRegion)
        val store = ObservabilityStore(backend.bucket, clusterState.tenant(), backend.prefix)
        return mapOf(
            "control_node_ip" to controlNode.privateIp,
            "store_bucket" to backend.bucket,
            "store_prefix" to backend.prefix,
            "traces_store_prefix" to store.tracesPrefix(),
            "metrics_store_prefix" to store.metricsPrefix(),
            "logs_store_prefix" to store.logsPrefix(),
        ) + locationOf(backend) +
            mapOf(
                "cluster_name" to clusterState.clusterLabelName(),
                "tenant" to clusterState.tenant(),
                Constants.Capabilities.CLUSTER_CONFIG_KEY to
                    NodeCapabilities
                        .from(clusterState)
                        .present()
                        .joinToString(Constants.Capabilities.CLUSTER_CONFIG_SEPARATOR) { it.serialName },
            )
    }

    /** Where the store is reached: the keys only the S3 fragments read. */
    private fun locationOf(backend: StoreBackend): Map<String, String> =
        when (backend) {
            is S3Backend -> mapOf("store_region" to backend.region, "store_endpoint" to backend.endpoint)
        }
}
