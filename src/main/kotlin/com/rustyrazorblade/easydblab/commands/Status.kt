package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.getLocalKubeconfigPath
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.output.displayClickHouseAccess
import com.rustyrazorblade.easydblab.output.displayObservabilityAccess
import com.rustyrazorblade.easydblab.output.displayRegistryAccess
import com.rustyrazorblade.easydblab.output.displayS3ManagerAccess
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.EMRService
import com.rustyrazorblade.easydblab.providers.aws.OpenSearchService
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.K3sService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.StressJobService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import java.io.File
import java.nio.file.Paths
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Displays comprehensive, human-readable environment status including:
 * - Nodes (cassandra, stress, control) with instance IDs, IPs, aliases, and live state
 * - Networking info (VPC, IGW, subnets, route tables)
 * - Security group rules (full ingress/egress)
 * - Kubernetes jobs running on K3s cluster
 * - Cassandra version (live via SSH, fallback to cached)
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Display full environment status"],
)
@Suppress("TooManyFunctions")
class Status :
    PicoCommand,
    KoinComponent {
    private val context: Context by inject()

    companion object {
        private val log = KotlinLogging.logger {}
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val POD_NAME_MAX_LENGTH = 40
        private const val HOURS_PER_DAY = 24
    }

    private val outputHandler: OutputHandler by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val securityGroupService: SecurityGroupService by inject()
    private val k3sService: K3sService by inject()
    private val k8sService: K8sService by inject()
    private val remoteOperationsService: RemoteOperationsService by inject()
    private val emrService: EMRService by inject()
    private val openSearchService: OpenSearchService by inject()
    private val stressJobService: StressJobService by inject()

    private val clusterState by lazy { clusterStateManager.load() }

    override fun execute() {
        if (!clusterStateManager.exists()) {
            outputHandler.handleMessage(
                "Cluster state does not exist yet. Run 'easy-db-lab init' first.",
            )
            return
        }

        displayClusterSection()
        displayNodesSection()
        displayNetworkingSection()
        displaySecurityGroupSection()
        displaySparkClusterSection()
        displayOpenSearchSection()
        displayS3BucketSection()
        displayKubernetesSection()
        displayStressJobsSection()
        displayObservabilitySection()
        displayClickHouseSection()
        displayS3ManagerSection()
        displayRegistrySection()
        displayCassandraVersionSection()
    }

    /**
     * Display cluster overview section
     */
    private fun displayClusterSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== CLUSTER STATUS ===")
        outputHandler.handleMessage("Cluster ID: ${clusterState.clusterId}")
        outputHandler.handleMessage("Name: ${clusterState.name}")
        outputHandler.handleMessage("Created: ${clusterState.createdAt.atZone(java.time.ZoneId.systemDefault()).format(DATE_FORMATTER)}")
        outputHandler.handleMessage("Infrastructure: ${clusterState.infrastructureStatus}")
    }

    /**
     * Display nodes section with live instance state from EC2
     */
    private fun displayNodesSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== NODES ===")

        val allInstanceIds = clusterState.getAllInstanceIds()

        // Get live instance states from EC2
        val instanceStates =
            if (allInstanceIds.isNotEmpty()) {
                runCatching {
                    ec2InstanceService
                        .describeInstances(allInstanceIds)
                        .associateBy { it.instanceId }
                }.getOrElse {
                    log.warn(it) { "Failed to get instance states from EC2" }
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        displayNodesByType(ServerType.Cassandra, "DATABASE NODES", instanceStates)
        displayNodesByType(ServerType.Stress, "APP NODES", instanceStates)
        displayNodesByType(ServerType.Control, "CONTROL NODES", instanceStates)
    }

    private fun displayNodesByType(
        serverType: ServerType,
        header: String,
        instanceStates: Map<String, com.rustyrazorblade.easydblab.providers.aws.InstanceDetails>,
    ) {
        val hosts = clusterState.hosts[serverType] ?: emptyList()
        if (hosts.isEmpty()) return

        outputHandler.handleMessage("")
        outputHandler.handleMessage("$header:")

        hosts.forEach { host ->
            val state = instanceStates[host.instanceId]?.state ?: "UNKNOWN"
            outputHandler.handleMessage(
                "  %-12s %-20s %-16s %-16s %-12s %s".format(
                    host.alias,
                    host.instanceId.ifEmpty { "(no id)" },
                    "${host.publicIp} (public)",
                    "${host.privateIp} (private)",
                    host.availabilityZone,
                    state.uppercase(),
                ),
            )
        }
    }

    /**
     * Display networking section
     */
    private fun displayNetworkingSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== NETWORKING ===")

        val infrastructure = clusterState.infrastructure
        if (infrastructure == null) {
            outputHandler.handleMessage("(no infrastructure data)")
            return
        }

        outputHandler.handleMessage("VPC:              ${infrastructure.vpcId}")
        outputHandler.handleMessage("Internet Gateway: ${infrastructure.internetGatewayId ?: "(none)"}")
        outputHandler.handleMessage("Subnets:          ${infrastructure.subnetIds.joinToString(", ")}")
        outputHandler.handleMessage("Route Tables:     ${infrastructure.routeTableId ?: "(default)"}")
    }

    /**
     * Display security group rules section
     */
    private fun displaySecurityGroupSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== SECURITY GROUP ===")

        val sgId = clusterState.infrastructure?.securityGroupId
        if (sgId == null) {
            outputHandler.handleMessage("(no security group configured)")
            return
        }

        val sgDetails =
            runCatching {
                securityGroupService.describeSecurityGroup(sgId)
            }.getOrNull()

        if (sgDetails == null) {
            outputHandler.handleMessage("Security Group: $sgId (unable to fetch details)")
            return
        }

        outputHandler.handleMessage("Security Group: ${sgDetails.securityGroupId} (${sgDetails.name})")

        outputHandler.handleMessage("")
        outputHandler.handleMessage("Inbound Rules:")
        displaySecurityRules(sgDetails.inboundRules)

        outputHandler.handleMessage("")
        outputHandler.handleMessage("Outbound Rules:")
        displaySecurityRules(sgDetails.outboundRules)
    }

    private fun displaySecurityRules(rules: List<SecurityGroupRuleInfo>) {
        if (rules.isEmpty()) {
            outputHandler.handleMessage("  (none)")
            return
        }

        rules.forEach { rule ->
            val portRange =
                when {
                    rule.fromPort == null && rule.toPort == null -> "All"
                    rule.fromPort == rule.toPort -> "${rule.fromPort}"
                    else -> "${rule.fromPort}-${rule.toPort}"
                }

            val cidrs = rule.cidrBlocks.joinToString(", ").ifEmpty { "(none)" }
            val description = rule.description ?: ""

            outputHandler.handleMessage(
                "  %-6s %-8s %-20s %s".format(
                    rule.protocol,
                    portRange,
                    cidrs,
                    description,
                ),
            )
        }
    }

    /**
     * Display Spark/EMR cluster section
     */
    private fun displaySparkClusterSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== SPARK CLUSTER ===")

        val emrCluster = clusterState.emrCluster
        if (emrCluster == null) {
            outputHandler.handleMessage("(no Spark cluster configured)")
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                emrService.getClusterStatus(emrCluster.clusterId).state
            }.getOrElse { emrCluster.state }

        outputHandler.handleMessage("Cluster ID:   ${emrCluster.clusterId}")
        outputHandler.handleMessage("Name:         ${emrCluster.clusterName}")
        outputHandler.handleMessage("State:        $liveState")
        emrCluster.masterPublicDns?.let {
            outputHandler.handleMessage("Master DNS:   $it")
        }
    }

    /**
     * Display OpenSearch domain section
     */
    private fun displayOpenSearchSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== OPENSEARCH DOMAIN ===")

        val openSearchDomain = clusterState.openSearchDomain
        if (openSearchDomain == null) {
            outputHandler.handleMessage("(no OpenSearch domain configured)")
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                openSearchService.describeDomain(openSearchDomain.domainName).state
            }.getOrElse { openSearchDomain.state }

        outputHandler.handleMessage("Domain Name:  ${openSearchDomain.domainName}")
        outputHandler.handleMessage("Domain ID:    ${openSearchDomain.domainId}")
        outputHandler.handleMessage("State:        $liveState")
        openSearchDomain.endpoint?.let {
            outputHandler.handleMessage("Endpoint:     https://$it")
        }
        openSearchDomain.dashboardsEndpoint?.let {
            outputHandler.handleMessage("Dashboards:   $it")
        }
    }

    /**
     * Display S3 bucket section
     */
    private fun displayS3BucketSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== S3 BUCKET ===")

        if (clusterState.s3Bucket.isNullOrBlank()) {
            outputHandler.handleMessage("(no S3 bucket configured)")
            return
        }

        val s3Path = clusterState.s3Path()
        outputHandler.handleMessage("Bucket:       ${clusterState.s3Bucket}")
        outputHandler.handleMessage("Cassandra:    ${s3Path.cassandra()}")
        outputHandler.handleMessage("ClickHouse:   ${s3Path.clickhouse()}")
        outputHandler.handleMessage("Spark:        ${s3Path.spark()}")
        outputHandler.handleMessage("EMR Logs:     ${s3Path.emrLogs()}")
    }

    /**
     * Display Kubernetes pods section
     */
    private fun displayKubernetesSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== KUBERNETES PODS ===")

        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            outputHandler.handleMessage("(no control node configured)")
            return
        }

        // Check if kubeconfig exists locally
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            outputHandler.handleMessage("(kubeconfig not found - K3s may not be initialized)")
            return
        }

        // Try to connect and list pods via K3sService
        val result = k3sService.listPods(controlHost, Paths.get(kubeconfigPath))

        result.fold(
            onSuccess = { pods ->
                if (pods.isEmpty()) {
                    outputHandler.handleMessage("(no pods running)")
                } else {
                    outputHandler.handleMessage(
                        "%-14s %-40s %-8s %-10s %-10s %s".format(
                            "NAMESPACE",
                            "NAME",
                            "READY",
                            "STATUS",
                            "RESTARTS",
                            "AGE",
                        ),
                    )
                    pods.forEach { pod ->
                        outputHandler.handleMessage(
                            "%-14s %-40s %-8s %-10s %-10s %s".format(
                                pod.namespace,
                                pod.name.take(POD_NAME_MAX_LENGTH),
                                pod.ready,
                                pod.status,
                                pod.restarts.toString(),
                                formatAge(pod.age),
                            ),
                        )
                    }
                }
            },
            onFailure = { e ->
                log.debug(e) { "Failed to get Kubernetes pods" }
                outputHandler.handleMessage("(unable to connect to K3s: ${e.message})")
            },
        )
    }

    /**
     * Display stress jobs section
     */
    private fun displayStressJobsSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== STRESS JOBS ===")

        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            outputHandler.handleMessage("(no control node configured)")
            return
        }

        val jobs: List<KubernetesJob> =
            try {
                stressJobService.listJobs(controlHost).getOrThrow()
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                log.debug(e) { "Failed to get stress jobs" }
                outputHandler.handleMessage("(unable to list stress jobs: ${e.message})")
                return
            } ?: run {
                outputHandler.handleMessage("(unable to list stress jobs)")
                return
            }

        if (jobs.isEmpty()) {
            outputHandler.handleMessage("(no stress jobs running)")
        } else {
            outputHandler.handleMessage(
                "%-40s %-12s %-12s %s".format(
                    "NAME",
                    "STATUS",
                    "COMPLETIONS",
                    "AGE",
                ),
            )
            jobs.forEach { job ->
                outputHandler.handleMessage(
                    "%-40s %-12s %-12s %s".format(
                        job.name.take(POD_NAME_MAX_LENGTH),
                        job.status,
                        job.completions,
                        formatAge(job.age),
                    ),
                )
            }
        }
    }

    /**
     * Display observability stack access information
     */
    private fun displayObservabilitySection() {
        val controlHost = clusterState.getControlHost() ?: return

        // Check if kubeconfig exists locally (indicates K3s is initialized)
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            return
        }

        outputHandler.displayObservabilityAccess(controlHost.privateIp)
    }

    /**
     * Display ClickHouse access information if ClickHouse is running
     */
    private fun displayClickHouseSection() {
        val controlHost = clusterState.getControlHost() ?: return

        // Get a db node IP for ClickHouse access (ClickHouse pods run on db nodes)
        val dbHosts = clusterState.hosts[ServerType.Cassandra]
        if (dbHosts.isNullOrEmpty()) {
            return
        }
        val dbNodeIp = dbHosts.first().privateIp

        // Check if kubeconfig exists locally (indicates K3s is initialized)
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            return
        }

        // Check if ClickHouse namespace has running pods
        val status = k8sService.getNamespaceStatus(controlHost, Constants.ClickHouse.NAMESPACE)
        status.onSuccess { podStatus ->
            // Only show if there are pods running (status contains pod info)
            if (podStatus.isNotBlank() && !podStatus.contains("No resources found")) {
                outputHandler.displayClickHouseAccess(dbNodeIp)
            }
        }
    }

    /**
     * Display S3Manager access information if K3s is initialized
     */
    private fun displayS3ManagerSection() {
        val controlHost = clusterState.getControlHost() ?: return

        // Check if kubeconfig exists locally (indicates K3s is initialized)
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            return
        }

        outputHandler.displayS3ManagerAccess(controlHost.privateIp, clusterState.s3Bucket ?: "")
    }

    /**
     * Display container registry access information if K3s is initialized
     */
    private fun displayRegistrySection() {
        val controlHost = clusterState.getControlHost() ?: return

        // Check if kubeconfig exists locally (indicates K3s is initialized)
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            return
        }

        outputHandler.displayRegistryAccess(controlHost.privateIp)
    }

    /**
     * Display Cassandra version section
     */
    private fun displayCassandraVersionSection() {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("=== CASSANDRA VERSION ===")

        val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()
        if (cassandraHosts.isEmpty()) {
            outputHandler.handleMessage("(no Cassandra nodes configured)")
            return
        }

        // Try to get live version from first available node
        val liveVersion = tryGetLiveVersion(cassandraHosts)

        if (liveVersion != null) {
            outputHandler.handleMessage("Version: $liveVersion (all nodes)")
        } else {
            // Fall back to cached version
            val cachedVersion = clusterState.default.version.ifEmpty { "unknown" }
            outputHandler.handleMessage("Version: $cachedVersion (cached - nodes unavailable)")
        }
    }

    private fun tryGetLiveVersion(hosts: List<ClusterHost>): String? {
        for (host in hosts) {
            val version =
                runCatching {
                    val sshHost = host.toHost()
                    val result = remoteOperationsService.getRemoteVersion(sshHost)
                    result.versionString
                }.onFailure { e ->
                    log.debug(e) { "Failed to get version from ${host.alias}" }
                }.getOrNull()

            // "current" means symlink not configured - treat as unavailable
            if (!version.isNullOrEmpty() && version != "current") {
                return version
            }
        }
        return null
    }

    private fun formatAge(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()

        return when {
            hours > HOURS_PER_DAY -> "${hours / HOURS_PER_DAY}d"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}

/**
 * Extension function to convert ClusterHost to Host for SSH operations
 */
private fun ClusterHost.toHost(): Host =
    Host(
        public = this.publicIp,
        private = this.privateIp,
        alias = this.alias,
        availabilityZone = this.availabilityZone,
    )
