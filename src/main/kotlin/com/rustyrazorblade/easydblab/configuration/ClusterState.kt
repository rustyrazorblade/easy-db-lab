package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.Init
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.services.TailSignal
import java.time.Instant
import java.util.UUID

/**
 * Tracking state across multiple commands
 */
const val CLUSTER_STATE = "state.json"

/**
 * Where an installed Cassandra version came from. The resolver picks one per install, and
 * `cassandra list` shows it, so a later reinstall or node replacement fetches the same build.
 */
enum class CassandraSource {
    /** An Apache release from dlcdn, with the archive as fallback. */
    ASF,

    /** A branch build from the cassandra-builds `nightly` release. */
    CASSANDRA_BUILDS,

    /** A build uploaded by `cassandra build` to the account bucket's catalog. */
    BUILD_CATALOG,

    /** A named build declared in the profile's `cassandra_versions/` directory. */
    PROFILE,

    /** A tarball URL given with `--url`. */
    URL,

    /** A git repository built on the node, from `--url` with `--branch`. */
    GIT,
}

/**
 * The record of one Cassandra version installed on a node: everything needed to install it again
 * without resolving the name a second time (node replacement, `--reinstall`).
 *
 * @property url the tarball or git URL, with any credentials redacted
 * @property javaSupported the JDKs this version runs on; [java] is one of them
 */
data class InstalledVersion(
    val version: String = "",
    val java: String = "",
    val javaSupported: List<String> = emptyList(),
    val python: String = "",
    val source: CassandraSource = CassandraSource.URL,
    val url: String = "",
    val branch: String? = null,
    val antFlags: String? = null,
)

/**
 * A db node's Cassandra state: the active [version] and its JDK, and every version installed on
 * the node keyed by name. An empty [version] means no version is active yet.
 */
data class NodeState(
    var version: String = "",
    var javaVersion: String = "",
    var installed: MutableMap<String, InstalledVersion> = mutableMapOf(),
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
    fun toHost(): Host =
        Host(
            public = publicIp,
            private = privateIp,
            alias = alias,
            availabilityZone = availabilityZone,
            instanceId = instanceId,
        )
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
 * Container Network Interface (CNI) selection for the K3s pod network.
 *
 * The datapath is mutually exclusive by construction — a cluster runs exactly one CNI.
 * [Cilium] is the default: Cilium in ENI IPAM native-routing mode, where pods receive
 * VPC-routable secondary IPs so cross-AZ traffic is routed by the VPC without encapsulation.
 * [Flannel] selects K3s's built-in VXLAN-overlay CNI instead (`--cni=flannel`). A saved state
 * that records no CNI predates Cilium and is read as [Flannel]. Modeled as an enum rather
 * than a boolean so future datapaths can be added without another flag.
 */
enum class CniMode {
    Cilium,
    Flannel,
}

/**
 * How the CLI reaches a cluster Service from the operator's machine — the cluster contract's
 * `exposure`. The service-access seam picks its implementation from it.
 *
 * [Socks] dials the node's private address through the per-client SOCKS tunnel (AWS, the default);
 * [Direct] dials it with no proxy (Tailscale, or the `direct` network mode); [PortForward] opens a
 * Kubernetes port-forward bound to `127.0.0.1` (a cluster easy-db-lab does not own). A saved state
 * that records none predates the field and is read as [Socks].
 */
enum class Exposure {
    Socks,
    Direct,
    PortForward,
    ;

    companion object {
        /**
         * The exposure an AWS `init` records: [Direct] when Tailscale reaches the nodes' private
         * addresses, otherwise [Socks]. [PortForward] is recorded by `init --local` and
         * `init --kubeconfig`, whose Services are reached by a port-forward on loopback.
         */
        fun atInit(tailscaleActive: Boolean): Exposure =
            when (tailscaleActive) {
                true -> Direct
                false -> Socks
            }
    }
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
 * The signals a pre-teardown save has already put in S3 and that must not be saved again.
 *
 * Only [TailSignal.LOGS] and [TailSignal.METRICS] are ever recorded, each the moment its flush
 * succeeds: those flushes leave Loki and Mimir stopped and cannot run twice. A `down` re-run after
 * an interrupted or failed save skips them and saves the rest again. `up` clears the record, since
 * the backends it starts take data the record does not cover.
 *
 * [saveCompletedAt] is set once every signal of a save succeeded. The infrastructure teardown starts
 * right after it, and no operation but `down` is supported on the cluster from then on, so the
 * record cannot go stale: a `down` re-run after a teardown that failed part-way skips the whole save,
 * without the SOCKS tunnel to a control node that may be gone, and goes straight to the teardown.
 *
 * @property signals each recorded signal and when its save finished.
 * @property saveCompletedAt when a save of every signal succeeded, or null while any signal is unsaved.
 */
data class TailFlushRecord(
    val signals: Map<TailSignal, SavedSignal> = emptyMap(),
    val saveCompletedAt: Instant? = null,
)

/**
 * One signal a pre-teardown save put in S3.
 *
 * @property completedAt when its flush finished.
 */
data class SavedSignal(
    val completedAt: Instant,
)

/**
 * Configuration from Init command to preserve cluster setup parameters
 * All fields have defaults to ensure backward compatibility with older state files
 */
data class InitConfig(
    val cassandraInstances: Int = 3,
    val stressInstances: Int = 0,
    val instanceType: String = Constants.InstanceTypes.DB,
    val stressInstanceType: String = Constants.InstanceTypes.APP,
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
    val controlInstanceType: String = Constants.InstanceTypes.CONTROL,
    val tags: Map<String, String> = mapOf(),
    val dbArch: String = "AMD64",
    val appArch: String = "AMD64",
    val controlArch: String = "AMD64",
    val sparkEnabled: Boolean = false,
    val sparkMasterInstanceType: String = Constants.InstanceTypes.SPARK,
    val sparkWorkerInstanceType: String = Constants.InstanceTypes.SPARK,
    val sparkWorkerCount: Int = 3,
    val sparkReleaseLabel: String = Constants.EMR.DEFAULT_RELEASE_LABEL,
    val opensearchEnabled: Boolean = false,
    val opensearchInstanceType: String = Constants.OpenSearch.DEFAULT_INSTANCE_TYPE,
    val opensearchInstanceCount: Int = Constants.OpenSearch.DEFAULT_INSTANCE_COUNT,
    val opensearchVersion: String = Constants.OpenSearch.DEFAULT_VERSION,
    val opensearchEbsSize: Int = Constants.OpenSearch.DEFAULT_EBS_SIZE_GB,
    val cidr: String? = null,
    val cni: CniMode = CniMode.Cilium,
    // Life-of-cluster telemetry redirect target. Null means local mode (the unchanged default):
    // the cluster stands up its own observability backends. Non-null means all four signals ship
    // to the external stack these endpoints describe and no local backends are stood up.
    val telemetryRedirect: TelemetryRedirect? = null,
    // The observability tenant the cluster's data belongs to. Fixed at init; a state file written
    // before tenants existed has no field and reads as the default tenant.
    val tenant: String = Constants.Observability.DEFAULT_TENANT,
    // Labels a node must carry to run the observability backends, Grafana and kube-state-metrics,
    // merged into the environment's placement (`Placement.from`). Empty adds nothing.
    val nodeLabels: Map<String, String> = emptyMap(),
    // The StorageClass every kit claim names (`KitStorage.from`). Null keeps kits on local volumes
    // on nodes easy-db-lab owns; set, it must reclaim with Retain.
    val kitStorageClass: String? = null,
    // A Kubernetes quantity overriding the environment's default kit claim size. Null keeps the
    // default (10Ti on local volumes, Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE otherwise).
    val kitStorageSize: String? = null,
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
                cni = init.cni,
                telemetryRedirect = init.resolvedTelemetryRedirect,
                tenant = init.tenant,
                nodeLabels = init.nodeLabels,
                kitStorageClass = init.kitStorageClass,
                kitStorageSize = init.kitStorageSize,
            )

        /**
         * The [InitConfig] `init --local` records: the node counts and the observability settings, no
         * region (there is no AWS account), Flannel (Cilium's ENI routing exists only on EC2), and
         * [hostArch], the architecture of this machine, for every role. The AWS-only fields keep their
         * defaults; `init --local` refuses the options that set them.
         */
        fun forLocal(
            init: Init,
            hostArch: Arch,
        ): InitConfig =
            InitConfig(
                cassandraInstances = init.resolvedDbCount,
                stressInstances = init.resolvedAppCount,
                region = "",
                name = init.name,
                tags = init.tags,
                dbArch = hostArch.name,
                appArch = hostArch.name,
                controlArch = hostArch.name,
                cni = CniMode.Flannel,
                telemetryRedirect = init.resolvedTelemetryRedirect,
                tenant = init.tenant,
                nodeLabels = init.nodeLabels,
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
    // True when the VPC came from `init --vpc`: it belongs to the user, so `down` never deletes it
    // and removes only the resources this cluster created in it.
    var vpcSupplied: Boolean = false,
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
    // Tailnet node ID of the control node, recorded by `tailscale start` so `down` deletes exactly
    // this cluster's device (every cluster's control node registers under the same hostname)
    var tailscaleDeviceId: String? = null,
    // Counter for stress job naming and port assignment
    var stressJobCounter: Int = 0,
    // Whether Tailscale was active on the local machine at init time.
    // When true, all cluster connections bypass the SOCKS proxy and use private IPs directly.
    var tailscaleActive: Boolean = false,
    // Names of kits that are currently started (K8s kits + EC2 services like cassandra)
    var runningKits: Set<String> = emptySet(),
    // The signals a pre-teardown save already put in S3, so a re-run of `down` skips them; cleared by `up`
    var tailFlush: TailFlushRecord? = null,
    // The Kubernetes context pinned for this cluster, recorded by `up` from the kubeconfig's
    // current-context. Every later command targets this context regardless of the user's current
    // kubectl context; a workspace with no recorded context falls back to the kubeconfig's
    // current-context.
    var kubeContext: String? = null,
    // The effective capability set `init` recorded (declared or probed, plus overrides). Null in a
    // state written before capabilities were recorded, which reads as AWS (NodeCapabilities.from).
    var capabilities: Set<NodeCapability>? = null,
    // Where each role's pods run. Null in a state written before it was recorded, or by an
    // environment easy-db-lab builds, which reads as one type=<role> label per node (RoleSelectors.from).
    var roleSelectors: RoleSelectors? = null,
    // The networks database kits accept clients from, as CIDR strings. Null in a state written
    // before it was recorded, or by an environment easy-db-lab builds, which reads as the VPC CIDR
    // and the K3s cluster CIDR (ClientNetworks.from).
    var clientNetworks: List<String>? = null,
    // The networks the user added with `init --client-network`, as CIDR strings. On the nodes
    // easy-db-lab builds they follow the VPC CIDR and the cluster CIDR (ClientNetworks.from); on
    // a cluster the user brings, the probe has already put them in clientNetworks.
    var extraClientNetworks: List<String> = emptyList(),
    // How the CLI reaches cluster Services; a state that records none predates the field and reads as Socks
    var exposure: Exposure = Exposure.Socks,
    // The execution environment `init` recorded; a state that records none predates the field and reads as EC2 + K3s
    var environment: Environment = Environment.DEFAULT,
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
     * Mark infrastructure as UP. The backends `up` starts take data a recorded tail save does not
     * cover, so the record is cleared and the next `down` saves every signal again.
     */
    fun markInfrastructureUp() {
        this.infrastructureStatus = InfrastructureStatus.UP
        this.tailFlush = null
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

    /**
     * The observability tenant this cluster's data belongs to. A state with no init configuration,
     * or one written before tenants existed, belongs to the default tenant.
     *
     * @throws IllegalStateException if state.json holds a tenant that breaks
     *   [Constants.Observability.TENANT_PATTERN] or is [Constants.Observability.RESERVED_TENANT].
     *   `init` checks it, but the value goes into JVM options
     *   and S3 keys, so a hand-edited one is refused here too.
     */
    fun tenant(): String {
        val tenant = initConfig?.tenant ?: Constants.Observability.DEFAULT_TENANT
        check(Regex(Constants.Observability.TENANT_PATTERN).matches(tenant)) {
            "Invalid tenant '$tenant' in state.json: a tenant must match ${Constants.Observability.TENANT_PATTERN}"
        }
        check(tenant != Constants.Observability.RESERVED_TENANT) {
            "Invalid tenant '$tenant' in state.json: '${Constants.Observability.RESERVED_TENANT}' is reserved"
        }
        return tenant
    }

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
    fun s3Path(): ClusterStoragePath = ClusterStoragePath.from(this)
}
