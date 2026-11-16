package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore

/**
 * The contents of the runtime `cluster-config` ConfigMap: the control node IP (log/OTLP destination),
 * AWS region, the account bucket and the Tempo, Mimir and Loki prefixes in it, the cluster name
 * (metric label), and the tenant every in-cluster producer and backend sends in `X-Scope-OrgID`.
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
        val store = ObservabilityStore(clusterState.s3Bucket.orEmpty(), clusterState.tenant())
        return mapOf(
            "control_node_ip" to controlNode.privateIp,
            "aws_region" to (clusterState.initConfig?.region ?: defaultRegion),
            "s3_bucket" to (clusterState.s3Bucket ?: ""),
            "traces_s3_prefix" to store.tracesPrefix(),
            "metrics_s3_prefix" to store.metricsPrefix(),
            "logs_s3_prefix" to store.logsPrefix(),
            "cluster_name" to clusterState.clusterLabelName(),
            "tenant" to clusterState.tenant(),
        )
    }
}
