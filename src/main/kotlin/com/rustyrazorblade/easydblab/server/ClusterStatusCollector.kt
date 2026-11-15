package com.rustyrazorblade.easydblab.server

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.WorkspaceKubeconfig
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitEndpointAddresses
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What a collection found: the typed [response] and the failures behind its gaps.
 *
 * A section that could not be read is absent (or has null rules) in [response], which is all the
 * REST API reports. The `status` command says more, so the reason travels beside the response:
 * [securityGroupError] when the security group could not be described, and [stressJobsError] when
 * the stress jobs could not be listed.
 */
data class StatusReport(
    val response: StatusResponse,
    val securityGroupError: String? = null,
    val stressJobsError: String? = null,
)

/**
 * Collects the cluster status report: the one place that fetches the facts from cluster state,
 * AWS, SSH and Kubernetes and builds the typed [StatusResponse].
 *
 * Both consumers read what this builds. [StatusCache] schedules a collection and caches the
 * result for `/status`; the `status` command renders the same response with `println`. Neither
 * fetches anything itself, so the two cannot disagree about a fact.
 *
 * ## Where the S3 paths point
 *
 * Each path is where that data is actually written, not one base applied to all:
 * - ClickHouse (`clickhouse/`) and Spark (`spark/`, where `spark submit` uploads jars) are in the
 *   per-cluster **data bucket** (`ClusterState.dataBucket`, falling back to the account bucket when
 *   a cluster has none), at its root.
 * - Cassandra (`cassandra/`) and EMR logs (`emr-logs/`, the cluster's `logUri`) are in the
 *   **account bucket** under the cluster prefix `clusters/<name>-<id>/`.
 * - Observability paths come from [ObservabilityStore] and are in the account bucket.
 */
class ClusterStatusCollector : KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val HOURS_PER_DAY = 24
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val RUNNING = "RUNNING"
    }

    private val context: Context by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val vpcService: VpcService by inject()
    private val k8sService: K8sService by inject()
    private val remoteOperationsService: RemoteOperationsService by inject()
    private val emrService: EMRService by inject()
    private val openSearchService: OpenSearchService by inject()
    private val stressJobService: StressJobService by inject()
    private val workspaceKitScanner: WorkspaceKitScanner by inject()

    /**
     * Collects every section for [state].
     *
     * @param includePods whether to list the Kubernetes pods; `status` does not show them, so it
     *   skips the call
     * @param kubernetesReachable whether the Kubernetes API can be reached (the SOCKS tunnel is
     *   up); when it cannot, the stress jobs, which come from it, are left out instead of failing
     */
    fun collect(
        state: ClusterState,
        includePods: Boolean = true,
        kubernetesReachable: Boolean = true,
    ): StatusReport {
        val instanceStates = fetchInstanceStates(state)
        val securityGroup = collectSecurityGroup(state)
        val stress = if (kubernetesReachable) collectStressJobs() else StressOutcome()
        val response =
            StatusResponse(
                cluster = buildClusterInfo(state),
                nodes = buildNodesInfo(state, instanceStates),
                networking = buildNetworkingInfo(state),
                securityGroup = securityGroup.info,
                spark = buildSparkInfo(state),
                opensearch = buildOpenSearchInfo(state),
                s3 = buildS3Info(state),
                kubernetes = if (includePods) buildKubernetesInfo() else null,
                stressJobs = stress.info,
                cassandraVersion = buildCassandraVersionInfo(state, instanceStates),
                accessInfo = buildAccessInfo(state),
            )
        return StatusReport(response, securityGroup.error, stress.error)
    }

    /**
     * The endpoints of every installed kit recorded as running, from each kit's `kit.yaml`.
     * A stopped kit is left out even when other pods of the kit, such as ClickHouse Keeper, run.
     */
    fun runningKits(state: ClusterState): List<KitAccess> =
        workspaceKitScanner
            .discover()
            .filter { it.name in state.runningKits }
            .sortedBy { it.name }
            .map { kitDir ->
                val resolved = KitEndpointAddresses.resolveInstalled(kitDir, state.hosts)
                KitAccess(
                    name = kitDir.name,
                    endpoints =
                        resolved.getOrDefault(emptyList()).map {
                            val address = KitEndpointAddresses.toEndpointAddress(it)
                            KitEndpointAccess(name = address.name, type = address.type, address = address.address)
                        },
                    endpointsUnavailable = resolved.exceptionOrNull()?.let { KitEndpointAddresses.UNREADABLE_DESCRIPTOR },
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
                instanceId = host.instanceId,
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

    private class SecurityGroupOutcome(
        val info: SecurityGroupInfo? = null,
        val error: String? = null,
    )

    private fun collectSecurityGroup(state: ClusterState): SecurityGroupOutcome {
        val sgId = state.infrastructure?.securityGroupId ?: return SecurityGroupOutcome()
        val described = runCatching { vpcService.describeSecurityGroup(sgId) }
        described.exceptionOrNull()?.let { log.warn(it) { "Failed to describe security group $sgId" } }
        val details = described.getOrNull()
        return SecurityGroupOutcome(
            info =
                SecurityGroupInfo(
                    securityGroupId = sgId,
                    name = details?.name,
                    inboundRules = details?.inboundRules?.map(::toRuleResponse),
                    outboundRules = details?.outboundRules?.map(::toRuleResponse),
                ),
            error = if (details == null) (described.exceptionOrNull()?.message ?: "security group not found") else null,
        )
    }

    private fun toRuleResponse(rule: SecurityGroupRuleInfo): SecurityGroupRuleResponse =
        SecurityGroupRuleResponse(
            protocol = rule.protocol,
            fromPort = rule.fromPort,
            toPort = rule.toPort,
            cidrBlocks = rule.cidrBlocks,
            description = rule.description,
        )

    private fun buildSparkInfo(state: ClusterState): SparkInfo? {
        val emrCluster = state.emrCluster ?: return null
        val liveState =
            runCatching {
                emrService.getClusterStatus(emrCluster.clusterId).state
            }.getOrElse {
                log.warn(it) { "Failed to get EMR cluster state, reporting the recorded state" }
                emrCluster.state
            }

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
            }.getOrElse {
                log.warn(it) { "Failed to get OpenSearch domain state, reporting the recorded state" }
                domain.state
            }

        return OpenSearchInfo(
            domainName = domain.domainName,
            domainId = domain.domainId,
            state = liveState,
            endpoint = domain.endpoint?.let { "https://$it" },
            dashboardsEndpoint = domain.dashboardsEndpoint,
        )
    }

    private fun buildS3Info(state: ClusterState): S3Info? {
        val accountBucket = state.s3Bucket
        if (accountBucket.isNullOrBlank()) return null
        val clusterPath = state.s3Path()
        val dataPath = ClusterS3Path.root(state.dataBucket.ifBlank { accountBucket })
        val store = ObservabilityStore.from(state)
        return S3Info(
            bucket = accountBucket,
            fullpath = "$accountBucket/${state.clusterPrefix()}",
            paths =
                S3Paths(
                    cassandra = clusterPath.cassandra().toString(),
                    clickhouse = dataPath.clickhouse().toString(),
                    spark = dataPath.spark().toString(),
                    emrLogs = clusterPath.emrLogs().toString(),
                    traces = "s3://${store.bucket}/${store.tracesPrefix()}",
                    profiles = "s3://${store.bucket}/${store.profilesPrefix()}",
                    metrics = "s3://${store.bucket}/${store.metricsPrefix()}/${store.tenant}",
                    logs = "s3://${store.bucket}/${store.logsPrefix()}",
                    annotations = store.annotationsRoot().toString(),
                ),
        )
    }

    private fun buildKubernetesInfo(): KubernetesInfo? {
        if (!WorkspaceKubeconfig.exists(context.workingDirectory)) return null

        return k8sService.listPods().fold(
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

    private class StressOutcome(
        val info: StressInfo? = null,
        val error: String? = null,
    )

    private fun collectStressJobs(): StressOutcome {
        if (!WorkspaceKubeconfig.exists(context.workingDirectory)) return StressOutcome()
        return runCatching {
            StressInfo(
                jobs =
                    stressJobService.listJobs().getOrThrow().map { job ->
                        StressJobInfo(
                            name = job.name,
                            status = job.status,
                            completions = job.completions,
                            age = formatAge(job.age),
                        )
                    },
            )
        }.fold(
            onSuccess = { StressOutcome(info = it) },
            onFailure = { e ->
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                log.warn(e) { "Failed to get stress jobs" }
                StressOutcome(error = e.message ?: "unknown error")
            },
        )
    }

    private fun buildCassandraVersionInfo(
        state: ClusterState,
        instanceStates: Map<String, InstanceDetails>,
    ): CassandraVersionInfo? {
        val cassandraHosts = state.hosts[ServerType.Cassandra]?.takeIf { it.isNotEmpty() } ?: return null

        // Only try the live version over SSH when a node is running. When EC2 could not be asked
        // (no instance states at all) every node is unknown, so try rather than report cached.
        val tryLive =
            instanceStates.isEmpty() ||
                cassandraHosts.any { host -> instanceStates[host.instanceId]?.state?.uppercase() == RUNNING }

        if (tryLive) {
            tryGetLiveVersion(cassandraHosts)?.let { return CassandraVersionInfo(version = it, source = "live") }
        }

        return CassandraVersionInfo(version = state.default.version.ifEmpty { "unknown" }, source = "cached")
    }

    private fun tryGetLiveVersion(hosts: List<ClusterHost>): String? {
        for (host in hosts) {
            val version =
                runCatching {
                    remoteOperationsService.getRemoteVersion(host.toHost()).versionString
                }.onFailure { e ->
                    log.debug(e) { "Failed to get version from ${host.alias}" }
                }.getOrNull()

            // "current" means the symlink is not configured: treat it as unavailable
            if (!version.isNullOrEmpty() && version != "current") {
                return version
            }
        }
        return null
    }

    private fun buildAccessInfo(state: ClusterState): AccessInfo? {
        val controlHost = state.getControlHost() ?: return null
        if (!WorkspaceKubeconfig.exists(context.workingDirectory)) return null

        val controlIp = controlHost.privateIp
        return AccessInfo(
            observability =
                ObservabilityAccess(
                    grafana = "http://$controlIp:${Constants.K8s.GRAFANA_PORT}",
                    mimir = "http://$controlIp:${Constants.K8s.MIMIR_HTTP_PORT}",
                    loki = "http://$controlIp:${Constants.K8s.LOKI_HTTP_PORT}",
                    tempo = "http://$controlIp:${Constants.K8s.TEMPO_PORT}",
                    pyroscope = "http://$controlIp:${Constants.K8s.PYROSCOPE_PORT}",
                ),
            kits = runningKits(state),
            // A cluster with no account bucket has nothing for the S3 manager to browse.
            s3Manager =
                state.s3Bucket?.takeIf { it.isNotBlank() }?.let {
                    val s3Path = state.s3Path()
                    S3ManagerAccess(
                        ui = "http://$controlIp:${Constants.K8s.S3MANAGER_PORT}/buckets/${s3Path.bucket}/${s3Path.getKey()}/",
                    )
                },
            registry = RegistryAccess(endpoint = "$controlIp:${Constants.K8s.REGISTRY_PORT}"),
        )
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
