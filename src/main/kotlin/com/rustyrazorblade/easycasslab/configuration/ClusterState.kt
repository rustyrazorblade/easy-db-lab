package com.rustyrazorblade.easycasslab.configuration

import java.time.Instant
import java.util.UUID

/**
 * Tracking state across multiple commands
 */
const val CLUSTER_STATE = "state.json"

data class NodeState(
    var version: String = "",
    var javaVersion: String = "",
)

/**
 * Represents host information for a single instance in the cluster
 */
data class ClusterHost(
    val publicIp: String,
    val privateIp: String,
    val alias: String,
    val availabilityZone: String,
)

/**
 * Infrastructure status tracking
 */
enum class InfrastructureStatus {
    UP,
    DOWN,
    UNKNOWN,
}

/**
 * Configuration from Init command to preserve cluster setup parameters
 * All fields have defaults to ensure backward compatibility with older state files
 */
data class InitConfig(
    val cassandraInstances: Int = 3,
    val stressInstances: Int = 0,
    val instanceType: String = "r3.2xlarge",
    val stressInstanceType: String = "c7i.2xlarge",
    val azs: List<String> = listOf(),
    val ami: String = "",
    val region: String = "us-west-2",
    val name: String = "cluster",
    val ebsType: String = "NONE",
    val ebsSize: Int = 256,
    val ebsIops: Int = 3000,
    val ebsThroughput: Int = 125,
    val ebsOptimized: Boolean = false,
    val open: Boolean = false,
    val controlInstances: Int = 1,
    val controlInstanceType: String = "t3.xlarge",
    val tags: Map<String, String> = mapOf(),
)

/**
 * Pure data class representing cluster state.
 * Persistence is handled by ClusterStateManager.
 */
data class ClusterState(
    var name: String,
    // if we fire up a new node and just tell it to go, it should use all the defaults
    var default: NodeState = NodeState(),
    // we also have a per-node mapping that lets us override, per node
    var nodes: MutableMap<Alias, NodeState> = mutableMapOf(),
    var versions: MutableMap<String, String>?,
    // Configuration from Init command
    var initConfig: InitConfig? = null,
    // Unique cluster identifier
    var clusterId: String = UUID.randomUUID().toString(),
    // Timestamps for lifecycle tracking
    var createdAt: Instant = Instant.now(),
    var lastAccessedAt: Instant = Instant.now(),
    // Infrastructure status tracking
    var infrastructureStatus: InfrastructureStatus = InfrastructureStatus.UNKNOWN,
    // All hosts in the cluster by server type
    var hosts: Map<ServerType, List<ClusterHost>> = emptyMap(),
    // VPC ID for the cluster - the core resource that contains all infrastructure
    var vpcId: String? = null,
) {
    /**
     * Update hosts from TFState
     */
    fun updateHosts(hosts: Map<ServerType, List<ClusterHost>>) {
        this.hosts = hosts
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Mark infrastructure as UP
     */
    fun markInfrastructureUp() {
        this.infrastructureStatus = InfrastructureStatus.UP
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Mark infrastructure as DOWN
     */
    fun markInfrastructureDown() {
        this.infrastructureStatus = InfrastructureStatus.DOWN
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Check if infrastructure is currently UP
     */
    fun isInfrastructureUp(): Boolean = infrastructureStatus == InfrastructureStatus.UP

    /**
     * Get the first control host, or null if none exists
     */
    fun getControlHost(): ClusterHost? = hosts[ServerType.Control]?.firstOrNull()

    /**
     * Validate if the current hosts match the stored hosts
     */
    fun validateHostsMatch(currentHosts: Map<ServerType, List<ClusterHost>>): Boolean {
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
}

/**
 * Extension function to create a ClusterS3Path from ClusterState and User configuration.
 * Provides convenient access to cluster-namespaced S3 paths.
 *
 * Example:
 * ```
 * val manager = ClusterStateManager()
 * val state = manager.load()
 * val s3Path = state.s3Path(userConfig)
 * val jarPath = s3Path.sparkJars().resolve("myapp.jar")
 * ```
 *
 * @param user The user configuration containing s3Bucket
 * @return A ClusterS3Path rooted at this cluster's namespace
 */
fun ClusterState.s3Path(user: User): ClusterS3Path = ClusterS3Path.from(this, user)
