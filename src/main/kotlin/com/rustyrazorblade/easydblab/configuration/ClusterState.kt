package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
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
) {
    /** Converts to the legacy [Host] type used by SSH/remote operations. */
    fun toHost(): Host = Host(public = publicIp, private = privateIp, alias = alias, availabilityZone = availabilityZone)
}

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
    val region: String,
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
    val instanceType: String = "i4i.xlarge",
    val stressInstanceType: String = "c6id.2xlarge",
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
    val controlInstanceType: String = "m5d.xlarge",
    val tags: Map<String, String> = mapOf(),
    val dbArch: String = "AMD64",
    val appArch: String = "AMD64",
    val controlArch: String = "AMD64",
    val sparkEnabled: Boolean = false,
    val sparkMasterInstanceType: String = "m5.xlarge",
    val sparkWorkerInstanceType: String = "m5.xlarge",
    val sparkWorkerCount: Int = 3,
    val sparkReleaseLabel: String = Constants.EMR.DEFAULT_RELEASE_LABEL,
    val opensearchEnabled: Boolean = false,
    val opensearchInstanceType: String = "t3.small.search",
    val opensearchInstanceCount: Int = 1,
    val opensearchVersion: String = "2.11",
    val opensearchEbsSize: Int = 100,
    val cidr: String? = null,
    val ciliumEnabled: Boolean = false,
) {
    companion object {
        /**
         * Factory method to create InitConfig from an Init command instance.
         * Encapsulates the transformation logic in a single location.
         *
         * Architectures are derived from each group's resolved instance type and passed in, rather
         * than read from a user flag — the architecture is a property of the instance type.
         *
         * @param init The Init command instance containing user-specified configuration
         * @param region The AWS region from user configuration
         * @param dbArch Architecture derived from the resolved database instance type
         * @param appArch Architecture derived from the resolved application instance type
         * @param controlArch Architecture derived from the control instance type
         * @return A new InitConfig with values from the Init command
         */
        fun fromInit(
            init: Init,
            region: String,
            dbArch: Arch,
            appArch: Arch,
            controlArch: Arch,
        ): InitConfig =
            InitConfig(
                cassandraInstances = init.resolvedDbCount,
                stressInstances = init.resolvedAppCount,
                instanceType = init.resolvedDbInstanceType,
                stressInstanceType = init.resolvedAppInstanceType,
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
                controlInstanceType = Init.DEFAULT_CONTROL_INSTANCE_TYPE,
                tags = init.tags,
                dbArch = dbArch.name,
                appArch = appArch.name,
                controlArch = controlArch.name,
                sparkEnabled = init.spark.enable,
                sparkMasterInstanceType = init.spark.masterInstanceType,
                sparkWorkerInstanceType = init.spark.workerInstanceType,
                sparkWorkerCount = init.spark.workerCount,
                sparkReleaseLabel = init.spark.releaseLabel,
                opensearchEnabled = init.opensearch.enable,
                opensearchInstanceType = init.opensearch.instanceType,
                opensearchInstanceCount = init.opensearch.instanceCount,
                opensearchVersion = init.opensearch.version,
                opensearchEbsSize = init.opensearch.ebsSize,
                cidr = init.cidr,
                ciliumEnabled = init.cilium,
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
    // S3 bucket for this environment (account-level bucket from User.s3Bucket)
    var s3Bucket: String? = null,
    // Per-cluster data bucket for ClickHouse data and CloudWatch metrics
    var dataBucket: String = "",
    // SHA-256 hashes of backed-up configuration files for incremental backup
    // Maps BackupTarget enum name to hex-encoded hash
    var backupHashes: Map<String, String> = emptyMap(),
    // Tailscale auth key ID for cleanup on teardown
    var tailscaleAuthKeyId: String? = null,
    // Counter for stress job naming and port assignment
    var stressJobCounter: Int = 0,
    // Whether Tailscale was active on the local machine at init time.
    // When true, all cluster connections bypass the SOCKS proxy and use private IPs directly.
    var tailscaleActive: Boolean = false,
    // Names of kits that are currently started (K8s kits + EC2 services like cassandra)
    var runningKits: Set<String> = emptySet(),
) {
    /**
     * Returns true when the db nodes are provisioned with Cassandra hosts.
     */
    fun isRunningCassandra(): Boolean = !hosts[ServerType.Cassandra].isNullOrEmpty()

    /**
     * Returns true when Tailscale was configured at init time and cluster connections bypass the SOCKS proxy.
     * Reads the stored [tailscaleActive] field from state.json.
     */
    fun isTailscaleEnabled(): Boolean = tailscaleActive

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
     * Update Tailscale auth key ID for cleanup on teardown
     */
    fun updateTailscaleAuthKeyId(keyId: String?) {
        this.tailscaleAuthKeyId = keyId
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

    /** Returns the cluster prefix path for S3: "clusters/{name}-{clusterId}". No trailing slash. */
    fun clusterPrefix(): String = "${Constants.S3.CLUSTERS_PREFIX}/$name-$clusterId"

    /** Returns the S3 metrics config ID: "edl-{name}-{clusterId}", truncated to API limit. */
    fun metricsConfigId(): String = "edl-$name-$clusterId".take(Constants.S3.MAX_METRICS_CONFIG_ID_LENGTH)

    /** Returns the per-cluster data bucket name: "easy-db-lab-data-{clusterId}". */
    fun dataBucketName(): String = "${Constants.S3.DATA_BUCKET_PREFIX}$clusterId"

    /** Returns the unique cluster label for OTel metric tagging: "{name}-{clusterId}". */
    fun clusterLabelName(): String = "$name-$clusterId"

    /** Returns all instance IDs across all host types (non-blank only). */
    fun getAllInstanceIds(): List<String> = hosts.values.flatten().mapNotNull { it.instanceId.takeIf { id -> id.isNotEmpty() } }

    /** Returns true when infrastructure status is UP. */
    fun isInfrastructureUp(): Boolean = infrastructureStatus == InfrastructureStatus.UP

    /** Returns the first control host, or null if none exists. */
    fun getControlHost(): ClusterHost? = hosts[ServerType.Control]?.firstOrNull()

    /** Returns hosts of [serverType] mapped to the legacy [Host] type. */
    fun getHosts(serverType: ServerType): List<Host> = hosts[serverType]?.map { it.toHost() } ?: emptyList()

    /**
     * Returns true when the stored hosts match [currentHosts] by alias and public IP.
     * Used to detect topology changes between runs.
     */
    fun validateHostsMatch(currentHosts: Map<ServerType, List<ClusterHost>>): Boolean {
        if (hosts.keys != currentHosts.keys) return false
        return hosts.all { (serverType, storedHosts) ->
            val current = currentHosts[serverType] ?: return false
            if (storedHosts.size != current.size) return false
            storedHosts.sortedBy { it.alias }.zip(current.sortedBy { it.alias }).all { (stored, curr) ->
                stored.alias == curr.alias && stored.publicIp == curr.publicIp
            }
        }
    }

    /**
     * Returns the S3 path abstraction for this cluster's account bucket.
     * @throws IllegalStateException if s3Bucket is not configured
     */
    fun s3Path(): ClusterS3Path = ClusterS3Path.from(this)
}
