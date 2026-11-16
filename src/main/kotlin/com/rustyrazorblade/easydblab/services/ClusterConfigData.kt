package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.store.S3Backend
import com.rustyrazorblade.easydblab.configuration.store.StoreBackend

/**
 * The contents of the runtime `cluster-config` ConfigMap: the object store in neutral `store_*`
 * keys ([StoreBackend]: bucket, prefix, region and endpoint) and the Tempo, Mimir and Loki prefixes
 * in it, the cluster name (metric label), the tenant every in-cluster producer and backend sends in
 * `X-Scope-OrgID`, the present node capabilities (serial names in enum order, comma-joined, so an
 * unchanged set never changes the hash; the account-level `aws-account` describes no node and is
 * left out), and, where the backends publish their ports on the control
 * node, that node's private IP.
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

    /** The key holding the control node's private IP, present only where the backends publish on it. */
    const val CONTROL_NODE_IP_KEY = "control_node_ip"

    /**
     * The ConfigMap's data for [clusterState] hosted as [hosting].
     *
     * @param defaultRegion the region used when the cluster state records none.
     */
    fun of(
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
        defaultRegion: String,
    ): Map<String, String> {
        val backend = StoreBackend.from(clusterState, defaultRegion)
        val store = ObservabilityStore(backend.bucket, clusterState.tenant(), backend.prefix)
        val nodeIp = publishedNodeIp(clusterState, hosting)
        return buildMap {
            if (nodeIp != null) put(CONTROL_NODE_IP_KEY, nodeIp)
            put("store_bucket", backend.bucket)
            put("store_prefix", backend.prefix)
            put("traces_store_prefix", store.tracesPrefix())
            put("metrics_store_prefix", store.metricsPrefix())
            put("logs_store_prefix", store.logsPrefix())
            putAll(locationOf(backend))
            put("cluster_name", clusterState.clusterLabelName())
            put("tenant", clusterState.tenant())
            put(
                Constants.Capabilities.CLUSTER_CONFIG_KEY,
                NodeCapabilities
                    .from(clusterState)
                    .present()
                    .filter { it.describesNode }
                    .joinToString(Constants.Capabilities.CLUSTER_CONFIG_SEPARATOR) { it.serialName },
            )
        }
    }

    /** Where the store is reached: the keys only the S3 fragments read. */
    private fun locationOf(backend: StoreBackend): Map<String, String> =
        when (backend) {
            is S3Backend -> mapOf("store_region" to backend.region, "store_endpoint" to backend.endpoint)
        }

    /**
     * The control node's private IP where [hosting] publishes the backends' ports on it (AWS), or
     * null where it does not: every in-cluster caller there reaches the backends by Service DNS.
     *
     * Fails on AWS when the state has no control node, because the callers outside the pod network
     * (redirected clusters, the Alloy eBPF agent, the db-node sidecar's Pyroscope agent, stress
     * jobs) reach the backends only at that IP (issue-1091 design D).
     */
    fun publishedNodeIp(
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
    ): String? =
        when (hosting.placement.publishOnNode) {
            false -> null
            true ->
                checkNotNull(clusterState.getControlHost()) {
                    "The backends publish their ports on the control node, but the cluster state has no control node: " +
                        "redirected clusters, the Alloy eBPF agent, the sidecar Pyroscope agent and stress jobs reach them only at its IP."
                }.privateIp
        }
}
