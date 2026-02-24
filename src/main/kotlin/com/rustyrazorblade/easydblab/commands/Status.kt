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
        eventBus.emit(
            Event.Status.ClusterInfo(
                clusterId = clusterState.clusterId,
                name = clusterState.name,
                createdAt = clusterState.createdAt.atZone(java.time.ZoneId.systemDefault()).format(DATE_FORMATTER),
                infrastructureStatus = clusterState.infrastructureStatus.toString(),
            ),
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

        eventBus.emit(
            Event.Status.NodesSection(
                databaseNodes = databaseNodes,
                appNodes = appNodes,
                controlNodes = controlNodes,
            ),
        )
    }

    private fun buildNodeDetails(
        serverType: ServerType,
        instanceStates: Map<String, com.rustyrazorblade.easydblab.providers.aws.InstanceDetails>,
    ): List<Event.Status.NodeDetail> {
        val hosts = clusterState.hosts[serverType] ?: emptyList()
        return hosts.map { host ->
            val state = instanceStates[host.instanceId]?.state ?: "UNKNOWN"
            Event.Status.NodeDetail(
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

        eventBus.emit(
            Event.Status.NetworkingInfo(
                vpcId = infrastructure.vpcId,
                internetGatewayId = infrastructure.internetGatewayId,
                subnetIds = infrastructure.subnetIds,
                routeTableId = infrastructure.routeTableId,
            ),
        )
    }

    /**
     * Display security group rules section
     */
    private fun displaySecurityGroupSection() {
        val sgId = clusterState.infrastructure?.securityGroupId
        if (sgId == null) {
            eventBus.emit(Event.Status.NoSecurityGroup)
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

        eventBus.emit(
            Event.Status.SecurityGroupInfo(
                sgId = sgDetails.securityGroupId,
                name = sgDetails.name,
                inboundRules = sgDetails.inboundRules.map { it.toSecurityRuleDetail() },
                outboundRules = sgDetails.outboundRules.map { it.toSecurityRuleDetail() },
            ),
        )
    }

    private fun SecurityGroupRuleInfo.toSecurityRuleDetail(): Event.Status.SecurityRuleDetail =
        Event.Status.SecurityRuleDetail(
            protocol = protocol,
            fromPort = fromPort,
            toPort = toPort,
            cidrBlocks = cidrBlocks,
            description = description,
        )

    /**
     * Display Spark/EMR cluster section
     */
    private fun displaySparkClusterSection() {
        val emrCluster = clusterState.emrCluster
        if (emrCluster == null) {
            eventBus.emit(Event.Status.NoSparkCluster)
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                emrService.getClusterStatus(emrCluster.clusterId).state
            }.getOrElse { emrCluster.state }

        eventBus.emit(
            Event.Status.SparkClusterInfo(
                clusterId = emrCluster.clusterId,
                clusterName = emrCluster.clusterName,
                state = liveState,
                masterPublicDns = emrCluster.masterPublicDns,
            ),
        )
    }

    /**
     * Display OpenSearch domain section
     */
    private fun displayOpenSearchSection() {
        val openSearchDomain = clusterState.openSearchDomain
        if (openSearchDomain == null) {
            eventBus.emit(Event.Status.NoOpenSearchDomain)
            return
        }

        // Try to get live status from AWS, fall back to cached state
        val liveState =
            runCatching {
                openSearchService.describeDomain(openSearchDomain.domainName).state.toString()
            }.getOrElse { openSearchDomain.state }

        eventBus.emit(
            Event.Status.OpenSearchInfo(
                domainName = openSearchDomain.domainName,
                domainId = openSearchDomain.domainId,
                state = liveState,
                endpoint = openSearchDomain.endpoint,
                dashboardsEndpoint = openSearchDomain.dashboardsEndpoint,
            ),
        )
    }

    /**
     * Display S3 bucket section
     */
    private fun displayS3BucketSection() {
        if (clusterState.s3Bucket.isNullOrBlank()) {
            eventBus.emit(Event.Status.NoS3Bucket)
            return
        }

        val s3Path = clusterState.s3Path()
        eventBus.emit(
            Event.Status.S3BucketInfo(
                bucket = clusterState.s3Bucket!!,
                fullPath = "${clusterState.s3Bucket}/${clusterState.clusterPrefix()}",
                cassandraPath = s3Path.cassandra().toString(),
                clickhousePath = s3Path.clickhouse().toString(),
                sparkPath = s3Path.spark().toString(),
                emrLogsPath = s3Path.emrLogs().toString(),
            ),
        )
    }

    /**
     * Display Kubernetes pods section
     */
    private fun displayKubernetesSection() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Status.KubernetesNoControlNode)
            return
        }

        // Check if kubeconfig exists locally
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) {
            eventBus.emit(Event.Status.KubernetesNoKubeconfig)
            return
        }

        // Try to connect and list pods via K3sService
        val result = k3sService.listPods(controlHost, Paths.get(kubeconfigPath))

        result.fold(
            onSuccess = { pods ->
                val podDetails =
                    pods.map { pod ->
                        Event.Status.PodDetail(
                            namespace = pod.namespace,
                            name = pod.name.take(POD_NAME_MAX_LENGTH),
                            ready = pod.ready,
                            status = pod.status,
                            restarts = pod.restarts,
                            age = formatAge(pod.age),
                        )
                    }
                eventBus.emit(Event.Status.KubernetesPodsSection(podDetails))
            },
            onFailure = { e ->
                log.debug(e) { "Failed to get Kubernetes pods" }
                eventBus.emit(Event.Status.KubernetesConnectionError(e.message ?: "unknown error"))
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

        val jobDetails =
            jobs.map { job ->
                Event.Status.StressJobDetail(
                    name = job.name.take(POD_NAME_MAX_LENGTH),
                    status = job.status,
                    completions = job.completions,
                    age = formatAge(job.age),
                )
            }
        eventBus.emit(Event.Status.StressJobsSection(jobDetails))
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
                controlHost.privateIp,
                Constants.K8s.GRAFANA_PORT,
                Constants.K8s.VICTORIAMETRICS_PORT,
                Constants.K8s.VICTORIALOGS_PORT,
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
            eventBus.emit(Event.Status.CassandraVersionLive(liveVersion))
        } else {
            // Fall back to cached version
            val cachedVersion = clusterState.default.version.ifEmpty { "unknown" }
            eventBus.emit(Event.Status.CassandraVersionCached(cachedVersion))
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
