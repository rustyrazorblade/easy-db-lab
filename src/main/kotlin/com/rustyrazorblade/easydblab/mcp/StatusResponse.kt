package com.rustyrazorblade.easydblab.mcp

import kotlinx.serialization.Serializable

/**
 * Top-level response for the /status endpoint.
 * Nullable sections are excluded from JSON output via Json { explicitNulls = false }.
 */
@Serializable
data class StatusResponse(
    val cluster: ClusterInfo,
    val nodes: NodesInfo,
    val networking: NetworkingInfo? = null,
    val securityGroup: SecurityGroupInfo? = null,
    val spark: SparkInfo? = null,
    val opensearch: OpenSearchInfo? = null,
    val s3: S3Info? = null,
    val kubernetes: KubernetesInfo? = null,
    val stressJobs: StressInfo? = null,
    val cassandraVersion: CassandraVersionInfo? = null,
    val accessInfo: AccessInfo? = null,
)

@Serializable
data class ClusterInfo(
    val clusterId: String,
    val name: String,
    val createdAt: String,
    val infrastructureStatus: String,
)

@Serializable
data class NodeInfo(
    val alias: String,
    val instanceId: String,
    val publicIp: String,
    val privateIp: String,
    val availabilityZone: String,
    val state: String? = null,
    val instanceType: String? = null,
)

@Serializable
data class NodesInfo(
    val database: List<NodeInfo>,
    val app: List<NodeInfo>,
    val control: List<NodeInfo>,
)

@Serializable
data class NetworkingInfo(
    val vpcId: String,
    val internetGatewayId: String?,
    val subnetIds: List<String>,
    val routeTableId: String?,
)

@Serializable
data class SecurityGroupRuleResponse(
    val protocol: String,
    val fromPort: Int?,
    val toPort: Int?,
    val cidrBlocks: List<String>,
    val description: String?,
)

@Serializable
data class SecurityGroupInfo(
    val securityGroupId: String,
    val name: String?,
    val inboundRules: List<SecurityGroupRuleResponse>?,
    val outboundRules: List<SecurityGroupRuleResponse>?,
)

@Serializable
data class SparkInfo(
    val clusterId: String,
    val clusterName: String,
    val state: String,
    val masterPublicDns: String? = null,
)

@Serializable
data class OpenSearchInfo(
    val domainName: String,
    val domainId: String,
    val state: String,
    val endpoint: String? = null,
    val dashboardsEndpoint: String? = null,
)

@Serializable
data class S3Info(
    val bucket: String,
    val fullpath: String,
    val paths: S3Paths,
)

@Serializable
data class S3Paths(
    val cassandra: String,
    val clickhouse: String,
    val spark: String,
    val emrLogs: String,
    val tempo: String,
    val pyroscope: String,
)

@Serializable
data class PodInfo(
    val namespace: String,
    val name: String,
    val ready: String,
    val status: String,
    val restarts: Int,
    val age: String,
)

@Serializable
data class KubernetesInfo(
    val pods: List<PodInfo>,
)

@Serializable
data class StressJobInfo(
    val name: String,
    val status: String,
    val completions: String,
    val age: String,
)

@Serializable
data class StressInfo(
    val jobs: List<StressJobInfo>,
)

@Serializable
data class CassandraVersionInfo(
    val version: String,
    val source: String,
)

@Serializable
data class AccessInfo(
    val observability: ObservabilityAccess? = null,
    val clickhouse: ClickHouseAccess? = null,
    val s3Manager: S3ManagerAccess? = null,
    val registry: RegistryAccess? = null,
)

@Serializable
data class ObservabilityAccess(
    val grafana: String,
    val victoriaMetrics: String,
    val victoriaLogs: String,
)

@Serializable
data class ClickHouseAccess(
    val playUi: String,
    val httpInterface: String,
    val nativePort: String,
)

@Serializable
data class S3ManagerAccess(
    val ui: String,
)

@Serializable
data class RegistryAccess(
    val endpoint: String,
)
