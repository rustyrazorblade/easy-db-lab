package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.EMRService
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.OpenSearchService
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupService
import com.rustyrazorblade.easydblab.services.K3sService
import com.rustyrazorblade.easydblab.services.K8sService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class StatusCacheTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockEc2InstanceService: EC2InstanceService
    private lateinit var mockSecurityGroupService: SecurityGroupService
    private lateinit var mockK3sService: K3sService
    private lateinit var mockK8sService: K8sService
    private lateinit var mockEmrService: EMRService
    private lateinit var mockOpenSearchService: OpenSearchService
    private lateinit var statusCache: StatusCache

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            clusterId = "test-cluster-id",
            createdAt = Instant.parse("2025-01-15T10:30:00Z"),
            infrastructureStatus = InfrastructureStatus.UP,
            default = NodeState(version = "5.0"),
            versions = null,
            hosts =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            ClusterHost(
                                publicIp = "54.1.2.3",
                                privateIp = "10.0.1.100",
                                alias = "db0",
                                availabilityZone = "us-west-2a",
                                instanceId = "i-db0",
                            ),
                        ),
                    ServerType.Stress to emptyList(),
                    ServerType.Control to
                        listOf(
                            ClusterHost(
                                publicIp = "54.1.2.4",
                                privateIp = "10.0.1.200",
                                alias = "control0",
                                availabilityZone = "us-west-2a",
                                instanceId = "i-control0",
                            ),
                        ),
                ),
            infrastructure =
                InfrastructureState(
                    vpcId = "vpc-abc123",
                    subnetIds = listOf("subnet-111", "subnet-222"),
                    securityGroupId = "sg-abc123",
                    internetGatewayId = "igw-def456",
                    routeTableId = "rtb-789",
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mockClusterStateManager }
                single { mockEc2InstanceService }
                single<SecurityGroupService> { mockSecurityGroupService }
                factory<K3sService> { mockK3sService }
                factory<K8sService> { mockK8sService }
                single { mockEmrService }
                single { mockOpenSearchService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        mockEc2InstanceService = mock()
        mockSecurityGroupService = mock()
        mockK3sService = mock()
        mockK8sService = mock()
        mockEmrService = mock()
        mockOpenSearchService = mock()

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockEc2InstanceService.describeInstances(any())).thenReturn(
            listOf(
                InstanceDetails(
                    instanceId = "i-db0",
                    state = "running",
                    publicIp = "54.1.2.3",
                    privateIp = "10.0.1.100",
                    availabilityZone = "us-west-2a",
                ),
                InstanceDetails(
                    instanceId = "i-control0",
                    state = "running",
                    publicIp = "54.1.2.4",
                    privateIp = "10.0.1.200",
                    availabilityZone = "us-west-2a",
                ),
            ),
        )
        whenever(mockSecurityGroupService.describeSecurityGroup(any())).thenReturn(
            SecurityGroupDetails(
                securityGroupId = "sg-abc123",
                name = "test-sg",
                description = "Test security group",
                vpcId = "vpc-abc123",
                inboundRules =
                    listOf(
                        SecurityGroupRuleInfo(
                            protocol = "tcp",
                            fromPort = 9042,
                            toPort = 9042,
                            cidrBlocks = listOf("10.0.0.0/16"),
                            description = "CQL",
                        ),
                    ),
                outboundRules = emptyList(),
            ),
        )
    }

    @Test
    fun `getStatus returns null before first refresh`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        val result = statusCache.getStatus()
        assertThat(result).isNull()
    }

    @Test
    fun `getStatus returns full JSON after refresh`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus()
        assertThat(result).isNotNull()

        val json = Json.parseToJsonElement(result!!).jsonObject
        assertThat(json.containsKey("cluster")).isTrue()
        assertThat(json.containsKey("nodes")).isTrue()
        assertThat(json.containsKey("networking")).isTrue()
        assertThat(json.containsKey("securityGroup")).isTrue()
    }

    @Test
    fun `cluster section contains correct data`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("cluster")
        assertThat(result).isNotNull()

        val cluster = Json.parseToJsonElement(result!!).jsonObject
        assertThat(cluster["clusterId"]?.jsonPrimitive?.content).isEqualTo("test-cluster-id")
        assertThat(cluster["name"]?.jsonPrimitive?.content).isEqualTo("test-cluster")
        assertThat(cluster["infrastructureStatus"]?.jsonPrimitive?.content).isEqualTo("UP")
    }

    @Test
    fun `nodes section maps server types correctly`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("nodes")
        assertThat(result).isNotNull()

        val nodes = Json.parseToJsonElement(result!!).jsonObject
        assertThat(nodes.containsKey("database")).isTrue()
        assertThat(nodes.containsKey("app")).isTrue()
        assertThat(nodes.containsKey("control")).isTrue()
    }

    @Test
    fun `node state is populated from EC2`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("nodes")
        val nodes = Json.parseToJsonElement(result!!).jsonObject
        val dbNodes = nodes["database"]!!.toString()

        assertThat(dbNodes).contains("RUNNING")
        assertThat(dbNodes).contains("db0")
        assertThat(dbNodes).contains("i-db0")
    }

    @Test
    fun `networking section contains infrastructure data`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("networking")
        assertThat(result).isNotNull()

        val networking = Json.parseToJsonElement(result!!).jsonObject
        assertThat(networking["vpcId"]?.jsonPrimitive?.content).isEqualTo("vpc-abc123")
        assertThat(networking["internetGatewayId"]?.jsonPrimitive?.content).isEqualTo("igw-def456")
    }

    @Test
    fun `security group includes rules from AWS`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("securityGroup")
        assertThat(result).isNotNull()

        val sg = Json.parseToJsonElement(result!!).jsonObject
        assertThat(sg["securityGroupId"]?.jsonPrimitive?.content).isEqualTo("sg-abc123")
        assertThat(sg["name"]?.jsonPrimitive?.content).isEqualTo("test-sg")
        assertThat(sg.containsKey("inboundRules")).isTrue()
    }

    @Test
    fun `invalid section throws IllegalArgumentException`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        assertThatThrownBy { statusCache.getStatus("nonexistent") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid section: 'nonexistent'")
    }

    @Test
    fun `nullable sections return null string when not configured`() {
        val stateWithoutOptionals =
            testClusterState.copy(
                emrCluster = null,
                openSearchDomain = null,
                s3Bucket = null,
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateWithoutOptionals)

        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        assertThat(statusCache.getStatus("spark")).isEqualTo("null")
        assertThat(statusCache.getStatus("opensearch")).isEqualTo("null")
        assertThat(statusCache.getStatus("s3")).isEqualTo("null")
    }

    @Test
    fun `null fields are omitted from full response`() {
        val stateWithoutOptionals =
            testClusterState.copy(
                emrCluster = null,
                openSearchDomain = null,
                s3Bucket = null,
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateWithoutOptionals)

        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus()
        assertThat(result).isNotNull()

        val json = Json.parseToJsonElement(result!!).jsonObject
        assertThat(json.containsKey("spark")).isFalse()
        assertThat(json.containsKey("opensearch")).isFalse()
        assertThat(json.containsKey("s3")).isFalse()
    }

    @Test
    fun `cassandraVersion source is cached when no running nodes`() {
        whenever(mockEc2InstanceService.describeInstances(any())).thenReturn(
            listOf(
                InstanceDetails(
                    instanceId = "i-db0",
                    state = "stopped",
                    publicIp = null,
                    privateIp = "10.0.1.100",
                    availabilityZone = "us-west-2a",
                ),
                InstanceDetails(
                    instanceId = "i-control0",
                    state = "stopped",
                    publicIp = null,
                    privateIp = "10.0.1.200",
                    availabilityZone = "us-west-2a",
                ),
            ),
        )

        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus("cassandraVersion")
        assertThat(result).isNotNull()

        val cv = Json.parseToJsonElement(result!!).jsonObject
        assertThat(cv["version"]?.jsonPrimitive?.content).isEqualTo("5.0")
        assertThat(cv["source"]?.jsonPrimitive?.content).isEqualTo("cached")
    }

    @Test
    fun `getStatus returns null when no cluster state exists`() {
        whenever(mockClusterStateManager.exists()).thenReturn(false)

        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        assertThat(statusCache.getStatus()).isNull()
    }

    @Test
    fun `forceRefresh updates cached data`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val firstResult = statusCache.getStatus("cluster")
        assertThat(firstResult).contains("test-cluster")

        // Update the state
        val updatedState = testClusterState.copy(name = "updated-cluster")
        whenever(mockClusterStateManager.load()).thenReturn(updatedState)
        statusCache.forceRefresh()

        val secondResult = statusCache.getStatus("cluster")
        assertThat(secondResult).contains("updated-cluster")
    }

    @Test
    fun `full response is valid JSON with all expected sections`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()

        val result = statusCache.getStatus()
        assertThat(result).isNotNull()

        // Parse and verify it's valid JSON
        val parsed = Json.parseToJsonElement(result!!)
        assertThat(parsed).isInstanceOf(JsonObject::class.java)

        val json = parsed.jsonObject
        // Required sections always present
        assertThat(json.containsKey("cluster")).isTrue()
        assertThat(json.containsKey("nodes")).isTrue()
    }

    @Test
    fun `stop prevents further refreshes`() {
        statusCache = StatusCache(refreshIntervalSeconds = 3600)
        statusCache.forceRefresh()
        statusCache.stop()

        // Should still have cached data
        assertThat(statusCache.getStatus()).isNotNull()
    }
}
