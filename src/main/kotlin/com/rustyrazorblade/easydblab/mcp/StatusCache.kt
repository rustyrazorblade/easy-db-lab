package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.kubernetes.getLocalKubeconfigPath
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.EMRService
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.OpenSearchService
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.K3sService
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Background cache for environment status data.
 *
 * Periodically fetches live data from AWS, SSH, and Kubernetes,
 * storing the result in memory for instant retrieval via the /status endpoint.
 */
class StatusCache(
    private val refreshIntervalSeconds: Long = DEFAULT_REFRESH_INTERVAL_SECONDS,
) : KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 30L
        private const val INITIALIZATION_TIMEOUT_SECONDS = 60L
        private const val HOURS_PER_DAY = 24
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val VALID_SECTIONS =
            listOf(
                "cluster",
                "nodes",
                "networking",
                "securityGroup",
                "spark",
                "opensearch",
                "s3",
                "kubernetes",
                "cassandraVersion",
                "accessInfo",
            )
    }

    private val context: Context by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val securityGroupService: SecurityGroupService by inject()
    private val k3sService: K3sService by inject()
    private val k8sService: K8sService by inject()
    private val remoteOperationsService: RemoteOperationsService by inject()
    private val emrService: EMRService by inject()
    private val openSearchService: OpenSearchService by inject()

    private val lock = ReentrantLock()
    private val timer = Timer("status-cache-refresh", true)
    private val initializedLatch = CountDownLatch(1)

    @Volatile
    private var cachedResponse: StatusResponse? = null

    private val json = Json { explicitNulls = false }

    /**
     * Start the background refresh cycle.
     * Performs an immediate fetch, then schedules recurring refreshes.
     */
    fun start() {
        log.info { "Starting status cache with ${refreshIntervalSeconds}s refresh interval" }
        refresh()
        scheduleNextRefresh()
    }

    /**
     * Stop the background refresh timer.
     */
    fun stop() {
        timer.cancel()
        log.info { "Status cache stopped" }
    }

    /**
     * Force an immediate refresh, resetting the timer.
     * Blocks until the refresh completes.
     */
    fun forceRefresh() {
        timer.purge()
        refresh()
        scheduleNextRefresh()
    }

    /**
     * Get the cached status as a JSON string.
     * Blocks until the cache has been populated at least once (up to 60 seconds).
     *
     * @param section Optional section name to return only that section
     * @return JSON string, or null if the cache could not be populated within the timeout
     * @throws IllegalArgumentException if the section name is invalid
     */
    fun getStatus(section: String? = null): String? {
        initializedLatch.await(INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val response = cachedResponse ?: return null
        if (section == null) {
            return json.encodeToString(StatusResponse.serializer(), response)
        }
        return serializeSection(response, section)
    }

    @Suppress("CyclomaticComplexity")
    private fun serializeSection(
        response: StatusResponse,
        section: String,
    ): String =
        when (section) {
            "cluster" -> json.encodeToString(ClusterInfo.serializer(), response.cluster)
            "nodes" -> json.encodeToString(NodesInfo.serializer(), response.nodes)
            "networking" ->
                response.networking?.let {
                    json.encodeToString(NetworkingInfo.serializer(), it)
                } ?: "null"
            "securityGroup" ->
                response.securityGroup?.let {
                    json.encodeToString(SecurityGroupInfo.serializer(), it)
                } ?: "null"
            "spark" ->
                response.spark?.let {
                    json.encodeToString(SparkInfo.serializer(), it)
                } ?: "null"
            "opensearch" ->
                response.opensearch?.let {
                    json.encodeToString(OpenSearchInfo.serializer(), it)
                } ?: "null"
            "s3" ->
                response.s3?.let {
                    json.encodeToString(S3Info.serializer(), it)
                } ?: "null"
            "kubernetes" ->
                response.kubernetes?.let {
                    json.encodeToString(KubernetesInfo.serializer(), it)
                } ?: "null"
            "cassandraVersion" ->
                response.cassandraVersion?.let {
                    json.encodeToString(CassandraVersionInfo.serializer(), it)
                } ?: "null"
            "accessInfo" ->
                response.accessInfo?.let {
                    json.encodeToString(AccessInfo.serializer(), it)
                } ?: "null"
            else ->
                throw IllegalArgumentException(
                    "Invalid section: '$section'. Valid sections: ${VALID_SECTIONS.joinToString(", ")}",
                )
        }

    private fun scheduleNextRefresh() {
        timer.purge()
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    refresh()
                    scheduleNextRefresh()
                }
            },
            refreshIntervalSeconds * Constants.Time.MILLIS_PER_SECOND,
        )
    }

    private fun refresh() {
        lock.withLock {
            log.debug { "Refreshing status cache" }
            try {
                if (!clusterStateManager.exists()) {
                    log.debug { "No cluster state found, skipping refresh" }
                    cachedResponse = null
                    initializedLatch.countDown()
                    return
                }

                val clusterState = clusterStateManager.load()
                cachedResponse = buildStatusResponse(clusterState)
                initializedLatch.countDown()
                log.debug { "Status cache refreshed" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to refresh status cache" }
            }
        }
    }

    private fun buildStatusResponse(state: ClusterState): StatusResponse {
        val instanceStates = fetchInstanceStates(state)

        return StatusResponse(
            cluster = buildClusterInfo(state),
            nodes = buildNodesInfo(state, instanceStates),
            networking = buildNetworkingInfo(state),
            securityGroup = buildSecurityGroupInfo(state),
            spark = buildSparkInfo(state),
            opensearch = buildOpenSearchInfo(state),
            s3 = buildS3Info(state),
            kubernetes = buildKubernetesInfo(state),
            cassandraVersion = buildCassandraVersionInfo(state, instanceStates),
            accessInfo = buildAccessInfo(state, instanceStates),
        )
    }

    private fun fetchInstanceStates(state: ClusterState): Map<String, InstanceDetails> {
        val allInstanceIds = state.getAllInstanceIds()
        if (allInstanceIds.isEmpty()) return emptyMap()

        return runCatching {
            ec2InstanceService
                .describeInstances(allInstanceIds)
                .associateBy { it.instanceId }
        }.getOrElse {
            log.warn(it) { "Failed to get instance states from EC2" }
            emptyMap()
        }
    }

    private fun buildClusterInfo(state: ClusterState): ClusterInfo =
        ClusterInfo(
            clusterId = state.clusterId,
            name = state.name,
            createdAt =
                state.createdAt
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_FORMATTER),
            infrastructureStatus = state.infrastructureStatus.name,
        )

    private fun buildNodesInfo(
        state: ClusterState,
        instanceStates: Map<String, InstanceDetails>,
    ): NodesInfo =
        NodesInfo(
            database = buildNodeList(state, ServerType.Cassandra, instanceStates),
            app = buildNodeList(state, ServerType.Stress, instanceStates),
            control = buildNodeList(state, ServerType.Control, instanceStates),
        )

    private fun buildNodeList(
        state: ClusterState,
        serverType: ServerType,
        instanceStates: Map<String, InstanceDetails>,
    ): List<NodeInfo> =
        (state.hosts[serverType] ?: emptyList()).map { host ->
            NodeInfo(
                alias = host.alias,
                instanceId = host.instanceId.ifEmpty { "" },
                publicIp = host.publicIp,
                privateIp = host.privateIp,
                availabilityZone = host.availabilityZone,
                state = instanceStates[host.instanceId]?.state?.uppercase(),
                instanceType = instanceStates[host.instanceId]?.instanceType,
            )
        }

    private fun buildNetworkingInfo(state: ClusterState): NetworkingInfo? {
        val infra = state.infrastructure ?: return null
        return NetworkingInfo(
            vpcId = infra.vpcId,
            internetGatewayId = infra.internetGatewayId,
            subnetIds = infra.subnetIds,
            routeTableId = infra.routeTableId,
        )
    }

    private fun buildSecurityGroupInfo(state: ClusterState): SecurityGroupInfo? {
        val sgId = state.infrastructure?.securityGroupId ?: return null
        val sgDetails =
            runCatching {
                securityGroupService.describeSecurityGroup(sgId)
            }.getOrNull()

        return SecurityGroupInfo(
            securityGroupId = sgId,
            name = sgDetails?.name,
            inboundRules =
                sgDetails?.inboundRules?.map { rule ->
                    SecurityGroupRuleResponse(
                        protocol = rule.protocol,
                        fromPort = rule.fromPort,
                        toPort = rule.toPort,
                        cidrBlocks = rule.cidrBlocks,
                        description = rule.description,
                    )
                },
            outboundRules =
                sgDetails?.outboundRules?.map { rule ->
                    SecurityGroupRuleResponse(
                        protocol = rule.protocol,
                        fromPort = rule.fromPort,
                        toPort = rule.toPort,
                        cidrBlocks = rule.cidrBlocks,
                        description = rule.description,
                    )
                },
        )
    }

    private fun buildSparkInfo(state: ClusterState): SparkInfo? {
        val emrCluster = state.emrCluster ?: return null
        val liveState =
            runCatching {
                emrService.getClusterStatus(emrCluster.clusterId).state
            }.getOrElse { emrCluster.state }

        return SparkInfo(
            clusterId = emrCluster.clusterId,
            clusterName = emrCluster.clusterName,
            state = liveState,
            masterPublicDns = emrCluster.masterPublicDns,
        )
    }

    private fun buildOpenSearchInfo(state: ClusterState): OpenSearchInfo? {
        val domain = state.openSearchDomain ?: return null
        val liveState =
            runCatching {
                openSearchService.describeDomain(domain.domainName).state.name
            }.getOrElse { domain.state }

        return OpenSearchInfo(
            domainName = domain.domainName,
            domainId = domain.domainId,
            state = liveState,
            endpoint = domain.endpoint?.let { "https://$it" },
            dashboardsEndpoint = domain.dashboardsEndpoint,
        )
    }

    private fun buildS3Info(state: ClusterState): S3Info? {
        if (state.s3Bucket.isNullOrBlank()) return null
        val s3Path = state.s3Path()
        return S3Info(
            bucket = state.s3Bucket!!,
            paths =
                S3Paths(
                    cassandra = s3Path.cassandra().toString(),
                    clickhouse = s3Path.clickhouse().toString(),
                    spark = s3Path.spark().toString(),
                    emrLogs = s3Path.emrLogs().toString(),
                ),
        )
    }

    private fun buildKubernetesInfo(state: ClusterState): KubernetesInfo? {
        val controlHost = state.getControlHost() ?: return null
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) return null

        val result = k3sService.listPods(controlHost, Paths.get(kubeconfigPath))
        return result.fold(
            onSuccess = { pods ->
                KubernetesInfo(
                    pods =
                        pods.map { pod ->
                            PodInfo(
                                namespace = pod.namespace,
                                name = pod.name,
                                ready = pod.ready,
                                status = pod.status,
                                restarts = pod.restarts,
                                age = formatAge(pod.age),
                            )
                        },
                )
            },
            onFailure = { e ->
                log.debug(e) { "Failed to get Kubernetes pods" }
                null
            },
        )
    }

    private fun buildCassandraVersionInfo(
        state: ClusterState,
        instanceStates: Map<String, InstanceDetails>,
    ): CassandraVersionInfo? {
        val cassandraHosts = state.hosts[ServerType.Cassandra] ?: return null
        if (cassandraHosts.isEmpty()) return null

        // Only try live version if at least one node is running
        val hasRunningNodes =
            cassandraHosts.any { host ->
                instanceStates[host.instanceId]?.state?.uppercase() == "RUNNING"
            }

        if (hasRunningNodes) {
            val liveVersion = tryGetLiveVersion(cassandraHosts)
            if (liveVersion != null) {
                return CassandraVersionInfo(version = liveVersion, source = "live")
            }
        }

        val cachedVersion = state.default.version.ifEmpty { "unknown" }
        return CassandraVersionInfo(version = cachedVersion, source = "cached")
    }

    private fun buildAccessInfo(
        state: ClusterState,
        instanceStates: Map<String, InstanceDetails>,
    ): AccessInfo? {
        val controlHost = state.getControlHost() ?: return null
        val kubeconfigPath = getLocalKubeconfigPath(context.workingDirectory.absolutePath)
        if (!File(kubeconfigPath).exists()) return null

        val controlIp = controlHost.privateIp

        val observability =
            ObservabilityAccess(
                grafana = "http://$controlIp:${Constants.K8s.GRAFANA_PORT}",
                victoriaMetrics = "http://$controlIp:${Constants.K8s.VICTORIAMETRICS_PORT}",
                victoriaLogs = "http://$controlIp:${Constants.K8s.VICTORIALOGS_PORT}",
            )

        val clickhouse = buildClickHouseAccess(state, controlHost, instanceStates)

        val s3Manager =
            S3ManagerAccess(
                ui = "http://$controlIp:${Constants.K8s.S3MANAGER_PORT}/buckets/${state.s3Bucket ?: ""}",
            )

        val registry =
            RegistryAccess(
                endpoint = "$controlIp:${Constants.K8s.REGISTRY_PORT}",
            )

        return AccessInfo(
            observability = observability,
            clickhouse = clickhouse,
            s3Manager = s3Manager,
            registry = registry,
        )
    }

    private fun buildClickHouseAccess(
        state: ClusterState,
        controlHost: ClusterHost,
        instanceStates: Map<String, InstanceDetails>,
    ): ClickHouseAccess? {
        val dbHosts = state.hosts[ServerType.Cassandra]
        if (dbHosts.isNullOrEmpty()) return null

        // Check if any Cassandra nodes are running
        val hasRunningCassandra =
            dbHosts.any { host ->
                instanceStates[host.instanceId]?.state?.uppercase() == "RUNNING"
            }
        if (!hasRunningCassandra) return null

        // Check if ClickHouse namespace has running pods
        val status = k8sService.getNamespaceStatus(controlHost, Constants.ClickHouse.NAMESPACE)
        val hasClickHousePods =
            status.fold(
                onSuccess = { podStatus ->
                    podStatus.isNotBlank() && !podStatus.contains("No resources found")
                },
                onFailure = { false },
            )

        if (!hasClickHousePods) return null

        val dbNodeIp = dbHosts.first().privateIp
        return ClickHouseAccess(
            playUi = "http://$dbNodeIp:${Constants.ClickHouse.HTTP_PORT}/play",
            httpInterface = "http://$dbNodeIp:${Constants.ClickHouse.HTTP_PORT}",
            nativePort = "$dbNodeIp:${Constants.ClickHouse.NATIVE_PORT}",
        )
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

private fun ClusterHost.toHost(): Host =
    Host(
        public = this.publicIp,
        private = this.privateIp,
        alias = this.alias,
        availabilityZone = this.availabilityZone,
    )
