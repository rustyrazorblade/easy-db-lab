package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.getLocalKubeconfigPath
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.K3sService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
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

    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val vpcService: VpcService by inject()
    private val k3sService: K3sService by inject()
    private val k8sService: K8sService by inject()
    private val remoteOperationsService: RemoteOperationsService by inject()
    private val emrService: EMRService by inject()
    private val openSearchService: OpenSearchService by inject()
    private val stressJobService: StressJobService by inject()

    private data class NodeDetail(
        val alias: String,
        val instanceId: String,
        val publicIp: String,
        val privateIp: String,
        val availabilityZone: String,
        val state: String,
    )

    private data class PodDetail(
        val namespace: String,
        val name: String,
        val ready: String,
        val status: String,
        val restarts: Int,
        val age: String,
    )

    private data class WorkloadPodDetail(
        val namespace: String,
        val name: String,
        val nodeName: String,
        val ready: String,
        val status: String,
    )

    private val clusterState by lazy { clusterStateManager.load() }

    override fun execute() {
        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Status.NoClusterState)
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
        displayWorkloadsSection()
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
        println(
            """
                |
                |=== CLUSTER STATUS ===
                |Cluster ID: ${clusterState.clusterId}
                |Name: ${clusterState.name}
                |Created: ${clusterState.createdAt.atZone(java.time.ZoneId.systemDefault()).format(DATE_FORMATTER)}
                |Infrastructure: ${clusterState.infrastructureStatus}
            """.trimMargin(),
        )
    }

    /**
     * Display nodes section with live instance state from EC2
     */
    private fun displayNodesSection() {
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

        val databaseNodes = buildNodeDetails(ServerType.Cassandra, instanceStates)
        val appNodes = buildNodeDetails(ServerType.Stress, instanceStates)
        val controlNodes = buildNodeDetails(ServerType.Control, instanceStates)

        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== NODES ===")

        fun formatGroup(
            header: String,
            nodes: List<NodeDetail>,
        ) {
            if (nodes.isEmpty()) return
            sb.appendLine("")
            sb.appendLine("$header:")
            nodes.forEach { node ->
                sb.appendLine(
                    "  %-12s %-20s %-16s %-16s %-12s %s".format(
                        node.alias,
                        node.instanceId.ifEmpty { "(no id)" },
                        "${node.publicIp} (public)",
                        "${node.privateIp} (private)",
                        node.availabilityZone,
                        node.state.uppercase(),
                    ),
                )
            }
        }
        formatGroup("DATABASE NODES", databaseNodes)
        formatGroup("APP NODES", appNodes)
        formatGroup("CONTROL NODES", controlNodes)
        println(sb.toString().trimEnd())
    }

    private fun buildNodeDetails(
        serverType: ServerType,
        instanceStates: Map<String, com.rustyrazorblade.easydblab.providers.aws.InstanceDetails>,
    ): List<NodeDetail> {
        val hosts = clusterState.hosts[serverType] ?: emptyList()
        return hosts.map { host ->
            val state = instanceStates[host.instanceId]?.state ?: "UNKNOWN"
            NodeDetail(
                alias = host.alias,
                instanceId = host.instanceId,
                publicIp = host.publicIp,
                privateIp = host.privateIp,
                availabilityZone = host.availabilityZone,
                state = state,
            )
        }
    }

    /**
     * Display networking section
     */
    private fun displayNetworkingSection() {
        val infrastructure = clusterState.infrastructure
        if (infrastructure == null) {
            eventBus.emit(Event.Status.NoInfrastructureData)
            return
        }

        println(
            """
                |
                |=== NETWORKING ===
                |VPC:              ${infrastructure.vpcId}
                |Internet Gateway: ${infrastructure.internetGatewayId ?: "(none)"}
                |Subnets:          ${infrastructure.subnetIds.joinToString(", ")}
                |Route Tables:     ${infrastructure.routeTableId ?: "(default)"}
            """.trimMargin(),
        )
    }

    /**
     * Display security group rules section
     */
    private fun displaySecurityGroupSection() {
        val sgId = clusterState.infrastructure?.securityGroupId
        if (sgId == null) {
            println("\n=== SECURITY GROUP ===\n(no security group)")
            return
        }

        val sgDetails =
            runCatching {
                vpcService.describeSecurityGroup(sgId)
            }.getOrNull()

        if (sgDetails == null) {
            eventBus.emit(Event.Status.SecurityGroupFetchFailed(sgId))
            return
        }

        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== SECURITY GROUP ===")
        sb.appendLine("Security Group: ${sgDetails.securityGroupId} (${sgDetails.name})")

        fun formatRules(rules: List<SecurityGroupRuleInfo>) {
            if (rules.isEmpty()) {
                sb.appendLine("  (none)")
                return
            }
            rules.forEach { rule ->
                val ports =
                    when {
                        rule.fromPort == null -> "all"
                        rule.fromPort == rule.toPort -> "${rule.fromPort}"
                        else -> "${rule.fromPort}-${rule.toPort}"
                    }
                val cidrs = rule.cidrBlocks.joinToString(", ")
                val desc = rule.description?.let { " ($it)" } ?: ""
                sb.appendLine("  ${rule.protocol}:$ports  $cidrs$desc")
            }
        }
        sb.appendLine("")
        sb.appendLine("Inbound Rules:")
        formatRules(sgDetails.inboundRules)
        sb.appendLine("")
        sb.appendLine("Outbound Rules:")
        formatRules(sgDetails.outboundRules)
        println(sb.toString().trimEnd())
    }

    /**
     * Display Spark/EMR cluster section
     */
    private fun displaySparkClusterSection() {
        val emrCluster = clusterState.emrCluster
        if (emrCluster == null) {
            println("\n=== SPARK CLUSTER ===\n(no Spark cluster configured)")
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                emrService.getClusterStatus(emrCluster.clusterId).state
            }.getOrElse { emrCluster.state }

        println(
            """
                |
                |=== SPARK CLUSTER ===
                |Cluster ID:  ${emrCluster.clusterId}
                |Name:        ${emrCluster.clusterName}
                |State:       $liveState
                |Master DNS:  ${emrCluster.masterPublicDns ?: "(not available)"}
            """.trimMargin(),
        )
    }

    /**
     * Display OpenSearch domain section
     */
    private fun displayOpenSearchSection() {
        val openSearchDomain = clusterState.openSearchDomain
        if (openSearchDomain == null) {
            println("\n=== OPENSEARCH ===\n(no OpenSearch domain configured)")
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                openSearchService.describeDomain(openSearchDomain.domainName).state.toString()
            }.getOrElse { openSearchDomain.state }

        println(
            """
                |
                |=== OPENSEARCH ===
                |Domain:     ${openSearchDomain.domainName} (${openSearchDomain.domainId})
                |State:      $liveState
                |Endpoint:   ${openSearchDomain.endpoint ?: "(not available)"}
                |Dashboards: ${openSearchDomain.dashboardsEndpoint ?: "(not available)"}
            """.trimMargin(),
        )
    }

    /**
     * Display S3 bucket section
     */
    private fun displayS3BucketSection() {
        if (clusterState.s3Bucket.isNullOrBlank()) {
            println("\n=== S3 BUCKET ===\n(no S3 bucket configured)")
            return
        }

        val dataPath = ClusterS3Path(clusterState.dataBucket)
        println(
            """
                |
                |=== S3 BUCKET ===
                |Bucket:    ${clusterState.s3Bucket}
                |Fullpath:     ${clusterState.s3Bucket}/${clusterState.clusterPrefix()}
                |  Cassandra: ${dataPath.cassandra()}
                |  ClickHouse: ${dataPath.clickhouse()}
                |  Spark: ${dataPath.spark()}
                |  EMR Logs: ${dataPath.emrLogs()}
            """.trimMargin(),
        )
    }

    /**
     * Display Kubernetes pods section
     */
    private fun displayKubernetesSection() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            println("\n=== KUBERNETES PODS ===")
            eventBus.emit(Event.Status.KubernetesNoControlNode)
            return
        }

        // Check if kubeconfig exists locally
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            println("\n=== KUBERNETES PODS ===")
            eventBus.emit(Event.Status.KubernetesNoKubeconfig)
            return
        }

        // Try to connect and list pods via K3sService
        val result = k3sService.listPods(controlHost, Paths.get(kubeconfigPath))

        result.fold(
            onSuccess = { pods ->
                val podDetails =
                    pods.map { pod ->
                        PodDetail(
                            namespace = pod.namespace,
                            name = pod.name.take(POD_NAME_MAX_LENGTH),
                            ready = pod.ready,
                            status = pod.status,
                            restarts = pod.restarts,
                            age = formatAge(pod.age),
                        )
                    }
                val sb = StringBuilder()
                sb.appendLine("")
                sb.appendLine("=== KUBERNETES PODS ===")
                if (podDetails.isEmpty()) {
                    sb.appendLine("  (no pods)")
                } else {
                    sb.appendLine("  %-20s %-40s %-8s %-12s %-8s %s".format("NAMESPACE", "NAME", "READY", "STATUS", "RESTARTS", "AGE"))
                    podDetails.forEach { pod ->
                        sb.appendLine(
                            "  %-20s %-40s %-8s %-12s %-8s %s".format(
                                pod.namespace,
                                pod.name,
                                pod.ready,
                                pod.status,
                                pod.restarts,
                                pod.age,
                            ),
                        )
                    }
                }
                println(sb.toString().trimEnd())
            },
            onFailure = { e ->
                log.debug(e) { "Failed to get Kubernetes pods" }
                eventBus.emit(Event.Status.KubernetesConnectionError(e.message ?: "unknown error"))
            },
        )
    }

    private fun displayWorkloadsSection() {
        val controlHost = clusterState.getControlHost() ?: return

        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            return
        }

        println()
        println("=== WORKLOADS ===")
        k8sService.listWorkloadPods(controlHost).fold(
            onSuccess = { pods ->
                val details =
                    pods.map { pod ->
                        WorkloadPodDetail(
                            namespace = pod.namespace,
                            name = pod.name,
                            nodeName = pod.nodeName,
                            ready = pod.ready,
                            status = pod.status,
                        )
                    }
                val sb = StringBuilder()
                if (details.isEmpty()) {
                    sb.appendLine("  (no workload pods running)")
                } else {
                    sb.appendLine("  %-20s %-40s %-20s %-8s %s".format("NAMESPACE", "NAME", "NODE", "READY", "STATUS"))
                    details.forEach { pod ->
                        sb.appendLine(
                            "  %-20s %-40s %-20s %-8s %s".format(
                                pod.namespace,
                                pod.name,
                                pod.nodeName,
                                pod.ready,
                                pod.status,
                            ),
                        )
                    }
                }
                println(sb.toString().trimEnd())
            },
            onFailure = { e ->
                log.debug(e) { "Failed to list workload pods" }
                eventBus.emit(Event.Status.WorkloadsError(e.message ?: "unknown error"))
            },
        )
    }

    /**
     * Display stress jobs section
     */
    private fun displayStressJobsSection() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Status.StressJobsNoControlNode)
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
                eventBus.emit(Event.Status.StressJobsError(e.message ?: "unknown error"))
                return
            } ?: run {
                eventBus.emit(Event.Status.StressJobsUnavailable)
                return
            }

        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("=== STRESS JOBS ===")
        if (jobs.isEmpty()) {
            sb.appendLine("  (no jobs)")
        } else {
            sb.appendLine("  %-40s %-12s %-12s %s".format("NAME", "STATUS", "COMPLETIONS", "AGE"))
            jobs.forEach { job ->
                sb.appendLine(
                    "  %-40s %-12s %-12s %s".format(
                        job.name.take(POD_NAME_MAX_LENGTH),
                        job.status,
                        job.completions,
                        formatAge(job.age),
                    ),
                )
            }
        }
        println(sb.toString().trimEnd())
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

        eventBus.emit(
            Event.Provision.ObservabilityAccessInfo(
                controlNodeIp = controlHost.privateIp,
                grafanaPort = Constants.K8s.GRAFANA_PORT,
                victoriaMetricsPort = Constants.K8s.VICTORIAMETRICS_PORT,
                victoriaLogsPort = Constants.K8s.VICTORIALOGS_PORT,
                tempoPort = Constants.K8s.TEMPO_PORT,
                pyroscopePort = Constants.K8s.PYROSCOPE_PORT,
            ),
        )
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
                eventBus.emit(
                    Event.Provision.ClickHouseAccessInfo(
                        dbNodeIp,
                        Constants.ClickHouse.HTTP_PORT,
                        Constants.ClickHouse.NATIVE_PORT,
                    ),
                )
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

        val s3Path = clusterState.s3Path()
        eventBus.emit(
            Event.Provision.S3ManagerAccessInfo(
                controlHost.privateIp,
                Constants.K8s.S3MANAGER_PORT,
                s3Path.bucket,
                s3Path.getKey(),
            ),
        )
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

        eventBus.emit(
            Event.Provision.RegistryAccessInfo(
                controlHost.privateIp,
                Constants.K8s.REGISTRY_PORT,
                Constants.Proxy.DEFAULT_SOCKS5_PORT,
            ),
        )
    }

    /**
     * Display Cassandra version section
     */
    private fun displayCassandraVersionSection() {
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()
        if (cassandraHosts.isEmpty()) {
            eventBus.emit(Event.Status.CassandraNoNodes)
            return
        }

        // Try to get live version from first available node
        val liveVersion = tryGetLiveVersion(cassandraHosts)

        if (liveVersion != null) {
            println("\n=== CASSANDRA VERSION ===\nVersion (live): $liveVersion")
        } else {
            // Fall back to cached version
            val cachedVersion = clusterState.default.version.ifEmpty { "unknown" }
            println("\n=== CASSANDRA VERSION ===\nVersion (cached): $cachedVersion")
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
