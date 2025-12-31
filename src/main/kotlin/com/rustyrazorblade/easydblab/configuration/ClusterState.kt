package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.commands.Init
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
    val instanceId: String = "",
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
 * EMR cluster state tracking for Spark jobs
 */
data class EMRClusterState(
    val clusterId: String,
    val clusterName: String,
    val masterPublicDns: String? = null,
    val state: String = "STARTING",
)

/**
 * OpenSearch domain state tracking for AWS-managed OpenSearch
 */
data class OpenSearchClusterState(
    val domainName: String,
    val domainId: String,
    val endpoint: String? = null,
    val dashboardsEndpoint: String? = null,
    val state: String = "Creating",
)

/**
 * Infrastructure resource IDs for cleanup and tracking
 * These are the AWS resource IDs that need to be cleaned up when the cluster is destroyed
 */
data class InfrastructureState(
    val vpcId: String,
    val subnetIds: List<String> = emptyList(),
    val securityGroupId: String? = null,
    val internetGatewayId: String? = null,
    val routeTableId: String? = null,
)

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
    val arch: String = "AMD64",
    val sparkEnabled: Boolean = false,
    val sparkMasterInstanceType: String = "m5.xlarge",
    val sparkWorkerInstanceType: String = "m5.xlarge",
    val sparkWorkerCount: Int = 3,
    val opensearchEnabled: Boolean = false,
    val opensearchInstanceType: String = "t3.small.search",
    val opensearchInstanceCount: Int = 1,
    val opensearchVersion: String = "2.11",
    val opensearchEbsSize: Int = 100,
) {
    companion object {
        /**
         * Factory method to create InitConfig from an Init command instance.
         * Encapsulates the transformation logic in a single location.
         *
         * @param init The Init command instance containing user-specified configuration
         * @param region The AWS region from user configuration
         * @return A new InitConfig with values from the Init command
         */
        fun fromInit(
            init: Init,
            region: String,
        ): InitConfig =
            InitConfig(
                cassandraInstances = init.cassandraInstances,
                stressInstances = init.stressInstances,
                instanceType = init.instanceType,
                stressInstanceType = init.stressInstanceType,
                azs = init.azs,
                ami = init.ami,
                region = region,
                name = init.name,
                ebsType = init.ebsType,
                ebsSize = init.ebsSize,
                ebsIops = init.ebsIops,
                ebsThroughput = init.ebsThroughput,
                ebsOptimized = init.ebsOptimized,
                open = init.open,
                controlInstances = 1,
                controlInstanceType = "t3.xlarge",
                tags = init.tags,
                arch = init.arch.name,
                sparkEnabled = init.spark.enable,
                sparkMasterInstanceType = init.spark.masterInstanceType,
                sparkWorkerInstanceType = init.spark.workerInstanceType,
                sparkWorkerCount = init.spark.workerCount,
                opensearchEnabled = init.opensearch.enable,
                opensearchInstanceType = init.opensearch.instanceType,
                opensearchInstanceCount = init.opensearch.instanceCount,
                opensearchVersion = init.opensearch.version,
                opensearchEbsSize = init.opensearch.ebsSize,
            )
    }
}

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
    // EMR cluster state for Spark jobs
    var emrCluster: EMRClusterState? = null,
    // OpenSearch domain state
    var openSearchDomain: OpenSearchClusterState? = null,
    // Infrastructure resource IDs for cleanup
    var infrastructure: InfrastructureState? = null,
    // S3 bucket for this environment (per-cluster bucket, created during 'up')
    var s3Bucket: String? = null,
    // SQS queue URL for log ingestion (receives S3 notifications for EMR logs)
    var sqsQueueUrl: String? = null,
    // SQS queue ARN for log ingestion (used for S3 bucket notification policy)
    var sqsQueueArn: String? = null,
    // SHA-256 hashes of backed-up configuration files for incremental backup
    // Maps BackupTarget enum name to hex-encoded hash
    var backupHashes: Map<String, String> = emptyMap(),
) {
    /**
     * Update hosts
     */
    fun updateHosts(hosts: Map<ServerType, List<ClusterHost>>) {
        this.hosts = hosts
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Update EMR cluster state
     */
    fun updateEmrCluster(emrCluster: EMRClusterState?) {
        this.emrCluster = emrCluster
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Update OpenSearch domain state
     */
    fun updateOpenSearchDomain(openSearchDomain: OpenSearchClusterState?) {
        this.openSearchDomain = openSearchDomain
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Update infrastructure state
     */
    fun updateInfrastructure(infrastructure: InfrastructureState?) {
        this.infrastructure = infrastructure
        this.vpcId = infrastructure?.vpcId
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Update SQS queue info for log ingestion
     */
    fun updateSqsQueue(
        queueUrl: String?,
        queueArn: String?,
    ) {
        this.sqsQueueUrl = queueUrl
        this.sqsQueueArn = queueArn
        this.lastAccessedAt = Instant.now()
    }

    /**
     * Get all instance IDs from all hosts for termination
     */
    fun getAllInstanceIds(): List<String> = hosts.values.flatten().mapNotNull { it.instanceId.takeIf { id -> id.isNotEmpty() } }

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
 * Extension function to create a ClusterS3Path from ClusterState.
 * Provides convenient access to S3 paths for this environment's bucket.
 *
 * Example:
 * ```
 * val manager = ClusterStateManager()
 * val state = manager.load()
 * val s3Path = state.s3Path()
 * val jarPath = s3Path.spark().resolve("myapp.jar")
 * ```
 *
 * @return A ClusterS3Path for this cluster's S3 bucket
 * @throws IllegalStateException if s3Bucket is not configured
 */
fun ClusterState.s3Path(): ClusterS3Path = ClusterS3Path.from(this)
