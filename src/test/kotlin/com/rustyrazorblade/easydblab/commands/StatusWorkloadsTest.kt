package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.WorkloadPod
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.K3sService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.StressJobService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.EMRService
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class StatusWorkloadsTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockK8sService: K8sService = mock()
    private val mockK3sService: K3sService = mock()
    private val mockEc2InstanceService: EC2InstanceService = mock()
    private val mockVpcService: VpcService = mock()
    private val mockSocksProxyService: SocksProxyService = mock()
    private val mockRemoteOperationsService: RemoteOperationsService = mock()
    private val mockEmrService: EMRService = mock()
    private val mockOpenSearchService: OpenSearchService = mock()
    private val mockStressJobService: StressJobService = mock()
    private lateinit var outputHandler: BufferedOutputHandler
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<K8sService> { mockK8sService }
                single<K3sService> { mockK3sService }
                single<EC2InstanceService> { mockEc2InstanceService }
                single<VpcService> { mockVpcService }
                single<SocksProxyService> { mockSocksProxyService }
                single<RemoteOperationsService> { mockRemoteOperationsService }
                single<EMRService> { mockEmrService }
                single<OpenSearchService> { mockOpenSearchService }
                single<StressJobService> { mockStressJobService }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        val ctx = getKoin().get<Context>()

        File(ctx.workingDirectory, Constants.K3s.LOCAL_KUBECONFIG).writeText("dummy")

        val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-control")
        val state =
            ClusterState(
                name = "test-cluster",
                clusterId = "test-123",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
                infrastructure =
                    InfrastructureState(
                        vpcId = "vpc-1",
                        region = "us-west-2",
                        internetGatewayId = "igw-1",
                        subnetIds = listOf("subnet-1"),
                        routeTableId = "rtb-1",
                        securityGroupId = "sg-1",
                    ),
                infrastructureStatus = InfrastructureStatus.UP,
                s3Bucket = "test-bucket",
                default = NodeState(version = "5.0"),
            )

        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockK3sService.listPods(any(), any())).thenReturn(Result.success(emptyList()))
        whenever(mockStressJobService.listJobs(any())).thenReturn(Result.success(emptyList()))

        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `workloads section shows pods grouped by namespace`() {
        whenever(mockK8sService.listWorkloadPods(any())).thenReturn(
            Result.success(
                listOf(
                    WorkloadPod(namespace = "clickhouse", name = "chi-pod-0", nodeName = "db-node-0", ready = "1/1", status = "Running"),
                    WorkloadPod(namespace = "clickhouse", name = "chi-pod-1", nodeName = "db-node-1", ready = "1/1", status = "Running"),
                    WorkloadPod(namespace = "default", name = "trino-worker-0", nodeName = "app-node-0", ready = "1/1", status = "Running"),
                ),
            ),
        )

        Status().execute()

        val output = stdout.toString()
        assertThat(output).contains("=== WORKLOADS ===")
        assertThat(output).contains("clickhouse")
        assertThat(output).contains("chi-pod-0")
        assertThat(output).contains("chi-pod-1")
        assertThat(output).contains("default")
        assertThat(output).contains("trino-worker-0")
    }

    @Test
    fun `workloads section shows no pods message when empty`() {
        whenever(mockK8sService.listWorkloadPods(any())).thenReturn(Result.success(emptyList()))

        Status().execute()

        val output = stdout.toString()
        assertThat(output).contains("=== WORKLOADS ===")
        assertThat(output).contains("(no workload pods running)")
    }

    @Test
    fun `workloads section shows error message when K8s call fails`() {
        whenever(mockK8sService.listWorkloadPods(any())).thenReturn(
            Result.failure(RuntimeException("connection refused")),
        )

        Status().execute()

        // Header is printed via println; error message comes through event bus
        assertThat(stdout.toString()).contains("=== WORKLOADS ===")
        assertThat(outputHandler.messages.joinToString("\n")).contains("connection refused")
    }

    @Test
    fun `workloads section shows node name for each pod`() {
        whenever(mockK8sService.listWorkloadPods(any())).thenReturn(
            Result.success(
                listOf(
                    WorkloadPod(namespace = "default", name = "my-app-pod", nodeName = "ip-10-0-2-0", ready = "1/1", status = "Running"),
                ),
            ),
        )

        Status().execute()

        assertThat(stdout.toString()).contains("ip-10-0-2-0")
    }
}
