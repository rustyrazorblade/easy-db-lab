package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterStatus
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class StatusTest : BaseKoinTest() {
    // Create mocks upfront so they can be referenced in module and test methods
    private val mockOutputHandler: OutputHandler = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockEc2InstanceService: EC2InstanceService = mock()
    private val mockVpcService: VpcService = mock()
    private val mockSocksProxyService: SocksProxyService = mock()
    private val mockRemoteOperationsService: RemoteOperationsService = mock()
    private val mockEmrService: EMRService = mock()
    private val mockStressJobService: StressJobService = mock()

    private val testHosts =
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
                    ClusterHost(
                        publicIp = "54.1.2.4",
                        privateIp = "10.0.1.101",
                        alias = "db1",
                        availabilityZone = "us-west-2b",
                        instanceId = "i-db1",
                    ),
                ),
            ServerType.Stress to
                listOf(
                    ClusterHost(
                        publicIp = "54.2.3.4",
                        privateIp = "10.0.2.100",
                        alias = "app0",
                        availabilityZone = "us-west-2a",
                        instanceId = "i-app0",
                    ),
                ),
            ServerType.Control to
                listOf(
                    ClusterHost(
                        publicIp = "54.3.4.5",
                        privateIp = "10.0.3.100",
                        alias = "control0",
                        availabilityZone = "us-west-2a",
                        instanceId = "i-control0",
                    ),
                ),
        )

    private val testInfrastructure =
        InfrastructureState(
            vpcId = "vpc-12345",
            region = "us-west-2",
            internetGatewayId = "igw-12345",
            subnetIds = listOf("subnet-a", "subnet-b", "subnet-c"),
            routeTableId = "rtb-12345",
            securityGroupId = "sg-12345",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<OutputHandler> { mockOutputHandler }
                single<ClusterStateManager> { mockClusterStateManager }
                single<EC2InstanceService> { mockEc2InstanceService }
                single<VpcService> { mockVpcService }
                single<SocksProxyService> { mockSocksProxyService }
                single<RemoteOperationsService> { mockRemoteOperationsService }
                single<EMRService> { mockEmrService }
                single<StressJobService> { mockStressJobService }
            },
        )

    @Test
    fun `execute displays message when cluster state does not exist`() {
        whenever(mockClusterStateManager.exists()).thenReturn(false)

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler).handleMessage(captor.capture())
        assertThat(captor.firstValue).contains("Cluster state does not exist")
    }

    @Test
    fun `execute displays cluster section`() {
        setupBasicClusterState()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== CLUSTER STATUS ===")
        assertThat(allMessages).contains("Cluster ID: test-123")
        assertThat(allMessages).contains("Name: test-cluster")
        assertThat(allMessages).contains("Infrastructure: UP")
    }

    @Test
    fun `execute displays nodes section with instance states`() {
        setupBasicClusterState()
        setupInstanceStates()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(10)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== NODES ===")
        assertThat(allMessages).contains("DATABASE NODES")
        assertThat(allMessages).contains("db0")
        assertThat(allMessages).contains("i-db0")
        assertThat(allMessages).contains("RUNNING")
    }

    @Test
    fun `execute displays networking section`() {
        setupBasicClusterState()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== NETWORKING ===")
        assertThat(allMessages).contains("vpc-12345")
        assertThat(allMessages).contains("igw-12345")
        assertThat(allMessages).contains("subnet-a")
    }

    @Test
    fun `execute displays security group section`() {
        setupBasicClusterState()
        setupSecurityGroup()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(10)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== SECURITY GROUP ===")
        assertThat(allMessages).contains("sg-12345")
        assertThat(allMessages).contains("Inbound Rules")
        assertThat(allMessages).contains("TCP")
        assertThat(allMessages).contains("22")
    }

    @Test
    fun `execute handles missing infrastructure gracefully`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = testHosts,
                infrastructure = null,
                infrastructureStatus = InfrastructureStatus.UP,
                default = NodeState(version = "5.0"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("(no infrastructure data)")
    }

    @Test
    fun `execute handles empty hosts gracefully`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = emptyMap(),
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                default = NodeState(version = "5.0"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        // Should not contain node headers if no nodes exist
        assertThat(allMessages).contains("=== CLUSTER STATUS ===")
    }

    @Test
    fun `execute displays cassandra version section with cached version when nodes unavailable`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = testHosts,
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                default = NodeState(version = "5.0.2"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
        // Make getRemoteVersion throw an exception to simulate unavailable nodes
        whenever(mockRemoteOperationsService.getRemoteVersion(any(), any()))
            .thenThrow(RuntimeException("Connection refused"))

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== CASSANDRA VERSION ===")
        assertThat(allMessages).contains("5.0.2")
        assertThat(allMessages).contains("cached")
    }

    @Test
    fun `execute displays kubernetes jobs message when no control node`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Cassandra to testHosts[ServerType.Cassandra]!!,
                    ),
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                default = NodeState(version = "5.0"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== KUBERNETES PODS ===")
        assertThat(allMessages).contains("(no control node configured)")
    }

    @Test
    fun `execute displays spark cluster section with live state`() {
        setupBasicClusterStateWithEmr()
        whenever(mockEmrService.getClusterStatus("j-TESTABC123"))
            .thenReturn(EMRClusterStatus("j-TESTABC123", "WAITING", null))

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(10)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== SPARK CLUSTER ===")
        assertThat(allMessages).contains("j-TESTABC123")
        assertThat(allMessages).contains("test-spark-cluster")
        assertThat(allMessages).contains("WAITING")
    }

    @Test
    fun `execute displays spark cluster section with cached state when EMR unavailable`() {
        setupBasicClusterStateWithEmr()
        whenever(mockEmrService.getClusterStatus("j-TESTABC123"))
            .thenThrow(RuntimeException("EMR service unavailable"))

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(10)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== SPARK CLUSTER ===")
        assertThat(allMessages).contains("j-TESTABC123")
        assertThat(allMessages).contains("RUNNING") // Falls back to cached state
    }

    @Test
    fun `execute handles missing spark cluster gracefully`() {
        setupBasicClusterState()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== SPARK CLUSTER ===")
        assertThat(allMessages).contains("(no Spark cluster configured)")
    }

    @Test
    fun `execute displays S3 bucket section`() {
        setupBasicClusterStateWithS3Bucket("test-bucket-123")

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(10)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== S3 BUCKET ===")
        assertThat(allMessages).contains("test-bucket-123")
        assertThat(allMessages).contains("spark")
        assertThat(allMessages).contains("cassandra")
    }

    @Test
    fun `execute handles missing S3 bucket gracefully`() {
        setupBasicClusterState()

        val command = Status()
        command.execute()

        val captor = argumentCaptor<String>()
        verify(mockOutputHandler, atLeast(5)).handleMessage(captor.capture())

        val allMessages = captor.allValues.joinToString("\n")
        assertThat(allMessages).contains("=== S3 BUCKET ===")
        assertThat(allMessages).contains("(no S3 bucket configured)")
    }

    private fun setupBasicClusterStateWithEmr() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = testHosts,
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                default = NodeState(version = "5.0"),
                emrCluster =
                    EMRClusterState(
                        clusterId = "j-TESTABC123",
                        clusterName = "test-spark-cluster",
                        masterPublicDns = "ec2-54-1-2-3.compute-1.amazonaws.com",
                        state = "RUNNING",
                    ),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
    }

    private fun setupBasicClusterState() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = testHosts,
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                default = NodeState(version = "5.0"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
    }

    private fun setupBasicClusterStateWithS3Bucket(bucketName: String) {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = testHosts,
                infrastructure = testInfrastructure,
                infrastructureStatus = InfrastructureStatus.UP,
                createdAt = Instant.parse("2024-01-15T10:00:00Z"),
                default = NodeState(version = "5.0"),
                s3Bucket = bucketName,
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)
    }

    private fun setupInstanceStates() {
        val instanceDetails =
            listOf(
                InstanceDetails(
                    instanceId = "i-db0",
                    state = "running",
                    publicIp = "54.1.2.3",
                    privateIp = "10.0.1.100",
                    availabilityZone = "us-west-2a",
                    instanceType = "r3.2xlarge",
                ),
                InstanceDetails(
                    instanceId = "i-db1",
                    state = "running",
                    publicIp = "54.1.2.4",
                    privateIp = "10.0.1.101",
                    availabilityZone = "us-west-2b",
                    instanceType = "r3.2xlarge",
                ),
                InstanceDetails(
                    instanceId = "i-app0",
                    state = "running",
                    publicIp = "54.2.3.4",
                    privateIp = "10.0.2.100",
                    availabilityZone = "us-west-2a",
                    instanceType = "m5.xlarge",
                ),
                InstanceDetails(
                    instanceId = "i-control0",
                    state = "running",
                    publicIp = "54.3.4.5",
                    privateIp = "10.0.3.100",
                    availabilityZone = "us-west-2a",
                    instanceType = "m5.xlarge",
                ),
            )

        whenever(mockEc2InstanceService.describeInstances(any())).thenReturn(instanceDetails)
    }

    private fun setupSecurityGroup() {
        val sgDetails =
            SecurityGroupDetails(
                securityGroupId = "sg-12345",
                name = "test-cluster-sg",
                description = "Security group for test cluster",
                vpcId = "vpc-12345",
                inboundRules =
                    listOf(
                        SecurityGroupRuleInfo(
                            protocol = "TCP",
                            fromPort = 22,
                            toPort = 22,
                            cidrBlocks = listOf("0.0.0.0/0"),
                            description = "SSH",
                        ),
                        SecurityGroupRuleInfo(
                            protocol = "TCP",
                            fromPort = 9042,
                            toPort = 9042,
                            cidrBlocks = listOf("10.0.0.0/8"),
                            description = "CQL",
                        ),
                    ),
                outboundRules =
                    listOf(
                        SecurityGroupRuleInfo(
                            protocol = "All",
                            fromPort = null,
                            toPort = null,
                            cidrBlocks = listOf("0.0.0.0/0"),
                            description = "All traffic",
                        ),
                    ),
            )

        whenever(mockVpcService.describeSecurityGroup("sg-12345")).thenReturn(sgDetails)
    }
}
