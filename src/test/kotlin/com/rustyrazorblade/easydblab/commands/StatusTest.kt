package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterStatus
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupDetails
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupRuleInfo
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.DefaultProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.DefaultCommandExecutor
import com.rustyrazorblade.easydblab.services.RequirementCheckDeps
import com.rustyrazorblade.easydblab.services.ResourceManager
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

class StatusTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockEc2InstanceService: EC2InstanceService = mock()
    private val mockVpcService: VpcService = mock()
    private val mockSocksProxyService: SocksProxyService = mock()
    private val mockRemoteOperationsService: RemoteOperationsService = mock()
    private val mockEmrService: EMRService = mock()
    private val mockStressJobService: StressJobService = mock()
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var proxyAvailability: ProxyAvailability
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    @BeforeEach
    fun captureStdout() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        proxyAvailability = getKoin().get()
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    private fun capturedOutput() = stdout.toString()

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
                single<ClusterStateManager> { mockClusterStateManager }
                single<EC2InstanceService> { mockEc2InstanceService }
                single<VpcService> { mockVpcService }
                single<SocksProxyService> { mockSocksProxyService }
                single<RemoteOperationsService> { mockRemoteOperationsService }
                single<EMRService> { mockEmrService }
                single<StressJobService> { mockStressJobService }
                single<ProxyAvailability> { DefaultProxyAvailability() }
            },
        )

    @Test
    fun `execute displays message when cluster state does not exist`() {
        whenever(mockClusterStateManager.exists()).thenReturn(false)
        Status().execute()
        // NoClusterState is still an event
        assertThat(outputHandler.messages.joinToString("\n")).contains("Cluster state does not exist")
    }

    @Test
    fun `execute displays cluster section`() {
        setupBasicClusterState()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== CLUSTER STATUS ===")
        assertThat(output).contains("Cluster ID: test-123")
        assertThat(output).contains("Name: test-cluster")
        assertThat(output).contains("Infrastructure: UP")
    }

    @Test
    fun `execute displays nodes section with instance states`() {
        setupBasicClusterState()
        setupInstanceStates()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== NODES ===")
        assertThat(output).contains("DATABASE NODES")
        assertThat(output).contains("db0")
        assertThat(output).contains("i-db0")
        assertThat(output).contains("RUNNING")
    }

    @Test
    fun `execute displays networking section`() {
        setupBasicClusterState()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== NETWORKING ===")
        assertThat(output).contains("vpc-12345")
        assertThat(output).contains("igw-12345")
        assertThat(output).contains("subnet-a")
    }

    @Test
    fun `execute displays security group section`() {
        setupBasicClusterState()
        setupSecurityGroup()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== SECURITY GROUP ===")
        assertThat(output).contains("sg-12345")
        assertThat(output).contains("Inbound Rules")
        assertThat(output).contains("tcp")
        assertThat(output).contains("22")
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
        Status().execute()
        // NoInfrastructureData is still an event
        assertThat(outputHandler.messages.joinToString("\n")).contains("(no infrastructure data)")
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
        Status().execute()
        assertThat(capturedOutput()).contains("=== CLUSTER STATUS ===")
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
        whenever(mockRemoteOperationsService.getRemoteVersion(any(), any()))
            .thenThrow(RuntimeException("Connection refused"))
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== CASSANDRA VERSION ===")
        assertThat(output).contains("5.0.2")
        assertThat(output).contains("cached")
    }

    @Test
    fun `execute displays spark cluster section with live state`() {
        setupBasicClusterStateWithEmr()
        whenever(mockEmrService.getClusterStatus("j-TESTABC123"))
            .thenReturn(EMRClusterStatus("j-TESTABC123", "WAITING", null))
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== SPARK CLUSTER ===")
        assertThat(output).contains("j-TESTABC123")
        assertThat(output).contains("test-spark-cluster")
        assertThat(output).contains("WAITING")
    }

    @Test
    fun `execute displays spark cluster section with cached state when EMR unavailable`() {
        setupBasicClusterStateWithEmr()
        whenever(mockEmrService.getClusterStatus("j-TESTABC123"))
            .thenThrow(RuntimeException("EMR service unavailable"))
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== SPARK CLUSTER ===")
        assertThat(output).contains("j-TESTABC123")
        assertThat(output).contains("RUNNING")
    }

    @Test
    fun `execute handles missing spark cluster gracefully`() {
        setupBasicClusterState()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== SPARK CLUSTER ===")
        assertThat(output).contains("(no Spark cluster configured)")
    }

    @Test
    fun `execute displays S3 bucket section`() {
        setupBasicClusterStateWithS3Bucket("test-bucket-123")
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== S3 BUCKET ===")
        assertThat(output).contains("test-bucket-123")
        assertThat(output).contains("spark")
        assertThat(output).contains("cassandra")
    }

    @Test
    fun `execute handles missing S3 bucket gracefully`() {
        setupBasicClusterState()
        Status().execute()
        val output = capturedOutput()
        assertThat(output).contains("=== S3 BUCKET ===")
        assertThat(output).contains("(no S3 bucket configured)")
    }

    // ========== DEGRADED STATUS (PROXY FAILURE) TESTS ==========
    //
    // `status` is the single command permitted to degrade rather than abort when the SOCKS
    // proxy cannot be established (see openspec/changes/up-fail-fast/design.md, decision D9).
    // These tests simulate that by recording a failure directly on ProxyAvailability — exactly
    // what DefaultCommandExecutor.checkRequirements() does for a command whose @RequiresProxy
    // sets tolerateFailure = true, before Status.execute() ever runs.

    @Test
    fun `execute still reports EC2 instance and VPC networking state when the proxy cannot be established`() {
        setupBasicClusterState()
        setupInstanceStates()
        proxyAvailability.recordFailure(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        assertThatThrownBy { Status().execute() }.isInstanceOf(StatusDegradedException::class.java)

        val output = capturedOutput()
        assertThat(output).contains("=== NODES ===")
        assertThat(output).contains("db0")
        assertThat(output).contains("=== NETWORKING ===")
        assertThat(output).contains("vpc-12345")
    }

    @Test
    fun `execute marks only the tunnel-dependent sections unavailable, citing the proxy failure`() {
        setupBasicClusterState()
        setupInstanceStates()
        proxyAvailability.recordFailure(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        assertThatThrownBy { Status().execute() }.isInstanceOf(StatusDegradedException::class.java)

        val events = outputHandler.messages.joinToString("\n")
        // The two sections sourced from the private Kubernetes API (Fabric8) are unavailable...
        assertThat(events).contains("STRESS JOBS")
        assertThat(events).contains("CLICKHOUSE")
        // ...each stating the proxy failure as its reason, not merely "unavailable".
        val reasonOccurrences = events.split("port never began accepting connections").size - 1
        assertThat(reasonOccurrences).isEqualTo(2)
        // The database version is sourced over SSH, which never traverses the tunnel, so it is
        // NOT marked unavailable even though the proxy is down.
        assertThat(events).doesNotContain("DATABASE VERSION")
    }

    @Test
    fun `execute still reports the database version over SSH when the proxy cannot be established`() {
        setupBasicClusterState()
        setupInstanceStates()
        // SSH does not go through the SOCKS tunnel, so a live version is still reachable.
        whenever(mockRemoteOperationsService.getRemoteVersion(any(), any()))
            .thenReturn(Version.fromString("5.0.2"))
        proxyAvailability.recordFailure(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        assertThatThrownBy { Status().execute() }.isInstanceOf(StatusDegradedException::class.java)

        val output = capturedOutput()
        // The section a proxy failure previously (wrongly) suppressed now renders over SSH.
        assertThat(output).contains("=== CASSANDRA VERSION ===")
        assertThat(output).contains("Version (live): 5.0.2")
    }

    @Test
    fun `execute does not abort before rendering the sections it can observe when the proxy cannot be established`() {
        setupBasicClusterState()
        setupInstanceStates()
        proxyAvailability.recordFailure(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        // The degraded report (cluster/nodes/networking + unavailable markers) must have been
        // fully rendered before execute() throws to signal the non-zero exit code.
        assertThatThrownBy { Status().execute() }.isInstanceOf(StatusDegradedException::class.java)

        assertThat(capturedOutput()).contains("=== CLUSTER STATUS ===", "=== NODES ===", "=== NETWORKING ===")
    }

    @Test
    fun `execute exits non-zero via the command executor when status degrades due to a proxy failure`() {
        setupBasicClusterState()
        setupInstanceStates()

        val mockUserConfigProvider: UserConfigProvider = mock()
        whenever(mockUserConfigProvider.isSetup()).thenReturn(true)
        val mockDockerClientProvider: DockerClientProvider = mock()
        val mockResourceManager: ResourceManager = mock()
        val mockSocksProxyService: SocksProxyService = mock()
        whenever(mockSocksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("port never began accepting connections — see socks5-proxy.log"))

        val commandExecutor: CommandExecutor =
            DefaultCommandExecutor(
                context = context,
                clusterStateManager = mockClusterStateManager,
                requirementCheckDeps =
                    RequirementCheckDeps(
                        userConfigProvider = mockUserConfigProvider,
                        dockerClientProvider = mockDockerClientProvider,
                    ),
                resourceManager = mockResourceManager,
                eventBus = getKoin().get(),
                socksProxyService = mockSocksProxyService,
                proxyAvailability = proxyAvailability,
            )

        // When - status is run through the real executor, exactly as it would be from the CLI
        val exitCode = commandExecutor.execute { Status() }

        // Then - a partial report must never be read by a script as a healthy cluster
        assertThat(exitCode).isNotEqualTo(0)
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
                    instanceType = "t3.medium",
                ),
            )
        whenever(mockEc2InstanceService.describeInstances(any())).thenReturn(instanceDetails)
    }

    private fun setupSecurityGroup() {
        val sgDetails =
            SecurityGroupDetails(
                securityGroupId = "sg-12345",
                name = "easy-db-lab-sg",
                description = "easy-db-lab security group",
                vpcId = "vpc-12345",
                inboundRules =
                    listOf(
                        SecurityGroupRuleInfo(
                            protocol = "tcp",
                            fromPort = 22,
                            toPort = 22,
                            cidrBlocks = listOf("0.0.0.0/0"),
                            description = "SSH access",
                        ),
                    ),
                outboundRules = emptyList(),
            )
        whenever(mockVpcService.describeSecurityGroup("sg-12345")).thenReturn(sgDetails)
    }
}
