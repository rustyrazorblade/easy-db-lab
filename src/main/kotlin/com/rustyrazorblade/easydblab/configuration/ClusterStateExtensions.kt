package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

// Extension functions for ClusterState providing host-lookup,
// infrastructure query helpers, and computed S3/naming values.

/**
 * Returns the cluster prefix path for S3 storage: "clusters/{name}-{clusterId}"
 * This is the canonical location for all cluster data in the account bucket.
 * Does NOT include a trailing slash -- callers needing one should append it.
 */
fun ClusterState.clusterPrefix(): String = "${Constants.S3.CLUSTERS_PREFIX}/$name-$clusterId"

/**
 * Returns the S3 metrics configuration ID for this cluster: "edl-{name}-{clusterId}"
 * Truncated to MAX_METRICS_CONFIG_ID_LENGTH to satisfy S3 API limits.
 */
fun ClusterState.metricsConfigId(): String = "edl-$name-$clusterId".take(Constants.S3.MAX_METRICS_CONFIG_ID_LENGTH)

/**
 * Returns the per-cluster data bucket name: "easy-db-lab-data-{clusterId}"
 */
fun ClusterState.dataBucketName(): String = "${Constants.S3.DATA_BUCKET_PREFIX}$clusterId"

/**
 * Get all instance IDs from all hosts for termination.
 */
fun ClusterState.getAllInstanceIds(): List<String> = hosts.values.flatten().mapNotNull { it.instanceId.takeIf { id -> id.isNotEmpty() } }

/**
 * Check if infrastructure is currently UP.
 */
fun ClusterState.isInfrastructureUp(): Boolean = infrastructureStatus == InfrastructureStatus.UP

/**
 * Get the first control host, or null if none exists.
 */
fun ClusterState.getControlHost(): ClusterHost? = hosts[ServerType.Control]?.firstOrNull()

/**
 * Validate if the current hosts match the stored hosts.
 */
fun ClusterState.validateHostsMatch(currentHosts: Map<ServerType, List<ClusterHost>>): Boolean {
    if (hosts.keys != currentHosts.keys) return false

    return hosts.all { (serverType, storedHosts) ->
        val current = currentHosts[serverType] ?: return false
        if (storedHosts.size != current.size) return false

        // Compare by alias and public IP (private IP might change on restart)
        storedHosts.sortedBy { it.alias }.zip(current.sortedBy { it.alias }).all { (stored, curr) ->
            stored.alias == curr.alias && stored.publicIp == curr.publicIp
        }
    }
}
