package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterResult
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.emr.model.InstanceGroupType

/**
 * Tests for EMRProvisioningService verifying correct EMR cluster creation.
 */
internal class EMRProvisioningServiceTest {
    private lateinit var mockEmrService: EMRService
    private lateinit var mockObjectStore: ObjectStore
    private lateinit var eventBus: EventBus
    private lateinit var capturedEvents: MutableList<Event>
    private lateinit var service: DefaultEMRProvisioningService

    companion object {
        private val testControlHost =
            ClusterHost(
                publicIp = "54.1.2.3",
                privateIp = "10.0.1.5",
                alias = "control0",
                availabilityZone = "us-west-2a",
                instanceId = "i-control",
            )
    }

    @BeforeEach
    fun setUp() {
        mockEmrService = mock()
        mockObjectStore = mock()
        capturedEvents = mutableListOf()
        eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope.event)
                }

                override fun close() {}
            },
        )
        val mockClusterStateManager = mock<ClusterStateManager>()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            ),
        )
        val testUser =
            User(
                region = "us-west-2",
                email = "test@example.com",
                keyName = "",
                awsProfile = "",
                awsAccessKey = "",
                awsSecret = "",
            )
        val templateService = TemplateService(mockClusterStateManager, testUser)
        service =
            DefaultEMRProvisioningService(
                mockEmrService,
                mockObjectStore,
                templateService,
                eventBus,
            )
    }

    @Test
    fun `provisionEmrCluster should create cluster with correct config`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-TEST123",
                clusterName = "test-spark",
                masterPublicDns = null,
                state = "STARTING",
            )
        val readyResult =
            EMRClusterResult(
                clusterId = "j-TEST123",
                clusterName = "test-spark",
                masterPublicDns = "ec2-1-2-3-4.compute-1.amazonaws.com",
                state = "WAITING",
            )

        whenever(mockEmrService.createCluster(any())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-TEST123")).thenReturn(readyResult)

        val result =
            service.provisionEmrCluster(
                clusterName = "test",
                masterInstanceType = "m5.2xlarge",
                workerInstanceType = "m5.4xlarge",
                workerCount = 5,
                subnetId = "subnet-123",
                securityGroupId = "sg-456",
                keyName = "my-key",
                clusterState = clusterState,
                tags = mapOf("env" to "test"),
            )

        assertThat(result.clusterId).isEqualTo("j-TEST123")
        assertThat(result.clusterName).isEqualTo("test-spark")
        assertThat(result.masterPublicDns).isEqualTo("ec2-1-2-3-4.compute-1.amazonaws.com")
        assertThat(result.state).isEqualTo("WAITING")
    }

    @Test
    fun `provisionEmrCluster should build correct EMRClusterConfig`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                clusterId = "test-id",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-ABC",
                clusterName = "myenv-spark",
                masterPublicDns = null,
                state = "STARTING",
            )
        val readyResult =
            EMRClusterResult(
                clusterId = "j-ABC",
                clusterName = "myenv-spark",
                masterPublicDns = "master.dns",
                state = "WAITING",
            )

        val configCaptor = argumentCaptor<EMRClusterConfig>()
        whenever(mockEmrService.createCluster(configCaptor.capture())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-ABC")).thenReturn(readyResult)

        service.provisionEmrCluster(
            clusterName = "myenv",
            masterInstanceType = "m5.2xlarge",
            workerInstanceType = "m5.4xlarge",
            workerCount = 5,
            subnetId = "subnet-123",
            securityGroupId = "sg-456",
            keyName = "my-key",
            clusterState = clusterState,
            tags = mapOf("env" to "test"),
        )

        val config = configCaptor.firstValue
        assertThat(config.clusterName).isEqualTo("myenv-spark")
        assertThat(config.masterInstanceType).isEqualTo("m5.2xlarge")
        assertThat(config.coreInstanceType).isEqualTo("m5.4xlarge")
        assertThat(config.coreInstanceCount).isEqualTo(5)
        assertThat(config.subnetId).isEqualTo("subnet-123")
        assertThat(config.ec2KeyName).isEqualTo("my-key")
        assertThat(config.additionalSecurityGroups).containsExactly("sg-456")
        assertThat(config.tags).containsEntry("env", "test")
        assertThat(config.logUri).isEqualTo("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/spark/emr-logs")
        assertThat(config.bootstrapActions).hasSize(1)
        assertThat(config.bootstrapActions.first().name).isEqualTo("Install OTel and Pyroscope Agents")
        assertThat(config.bootstrapActions.first().scriptS3Path).contains("bootstrap-otel.sh")

        // Verify spark-defaults and spark-env classifications are included
        assertThat(config.configurations).hasSize(2)
        val sparkDefaults = config.configurations.first { it.classification == "spark-defaults" }
        assertThat(sparkDefaults.classification).isEqualTo("spark-defaults")
        assertThat(sparkDefaults.properties["spark.driver.extraJavaOptions"])
            .contains("-javaagent:/opt/otel/opentelemetry-javaagent.jar")
            .contains("-javaagent:/opt/pyroscope/pyroscope.jar")
            .contains("-Dpyroscope.server.address=http://10.0.1.5:4040")
        assertThat(sparkDefaults.properties["spark.executor.extraJavaOptions"])
            .contains("-javaagent:/opt/otel/opentelemetry-javaagent.jar")
            .contains("-javaagent:/opt/pyroscope/pyroscope.jar")
        assertThat(sparkDefaults.properties["spark.driverEnv.OTEL_SERVICE_NAME"]).isEqualTo("spark")
        assertThat(sparkDefaults.properties["spark.executorEnv.OTEL_LOGS_EXPORTER"]).isEqualTo("otlp")
        assertThat(sparkDefaults.properties["spark.yarn.appMasterEnv.OTEL_TRACES_EXPORTER"]).isEqualTo("otlp")

        // Verify spark-env classification for Pyroscope hostname labeling
        val sparkEnv = config.configurations.first { it.classification == "spark-env" }
        assertThat(sparkEnv.configurations).hasSize(1)
        val exportConfig = sparkEnv.configurations.first()
        assertThat(exportConfig.classification).isEqualTo("export")
        assertThat(exportConfig.properties).containsKey("PYROSCOPE_LABELS")
    }

    @Test
    fun `provisionEmrCluster should call createCluster then waitForClusterReady`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-SEQ",
                clusterName = "test-spark",
                masterPublicDns = null,
                state = "STARTING",
            )
        val readyResult =
            EMRClusterResult(
                clusterId = "j-SEQ",
                clusterName = "test-spark",
                masterPublicDns = "master.dns",
                state = "WAITING",
            )

        whenever(mockEmrService.createCluster(any())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-SEQ")).thenReturn(readyResult)

        service.provisionEmrCluster(
            clusterName = "test",
            masterInstanceType = "m5.xlarge",
            workerInstanceType = "m5.xlarge",
            workerCount = 3,
            subnetId = "subnet-123",
            securityGroupId = "sg-456",
            keyName = "my-key",
            clusterState = clusterState,
            tags = emptyMap(),
        )

        verify(mockEmrService).createCluster(any())
        verify(mockEmrService).waitForClusterReady("j-SEQ")
    }

    @Test
    fun `provisionEmrCluster should propagate errors from createCluster`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        whenever(mockEmrService.createCluster(any()))
            .thenThrow(RuntimeException("EMR API unavailable"))

        assertThatThrownBy {
            service.provisionEmrCluster(
                clusterName = "test",
                masterInstanceType = "m5.xlarge",
                workerInstanceType = "m5.xlarge",
                workerCount = 3,
                subnetId = "subnet-123",
                securityGroupId = "sg-456",
                keyName = "my-key",
                clusterState = clusterState,
                tags = emptyMap(),
            )
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("EMR API unavailable")
    }

    @Test
    fun `provisionEmrCluster should emit diagnostics and re-throw when waitForClusterReady fails`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                clusterId = "test-id",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-FAIL",
                clusterName = "test-spark",
                masterPublicDns = null,
                state = "STARTING",
            )

        whenever(mockEmrService.createCluster(any())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-FAIL"))
            .thenThrow(IllegalStateException("Cluster failed: TERMINATED_WITH_ERRORS"))
        // listInstances throws to trigger the fallback path
        whenever(mockEmrService.listInstances(eq("j-FAIL"), any()))
            .thenThrow(RuntimeException("Cluster already terminated"))

        assertThatThrownBy {
            service.provisionEmrCluster(
                clusterName = "test",
                masterInstanceType = "m5.xlarge",
                workerInstanceType = "m5.xlarge",
                workerCount = 3,
                subnetId = "subnet-123",
                securityGroupId = "sg-456",
                keyName = "my-key",
                clusterState = clusterState,
                tags = emptyMap(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("TERMINATED_WITH_ERRORS")

        // Verify diagnostics were attempted
        verify(mockEmrService).listInstances(eq("j-FAIL"), eq(InstanceGroupType.MASTER))

        // Verify diagnostic events were emitted
        assertThat(capturedEvents).anyMatch { it is Event.Emr.BootstrapFailureDiagnosing }
        assertThat(capturedEvents).anyMatch { it is Event.Emr.BootstrapFailureLogUnavailable }
        assertThat(capturedEvents).anyMatch { it is Event.Emr.BootstrapFailureDebugInstructions }
    }

    @Test
    fun `diagnostics should emit unavailable when no master instances found`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                clusterId = "test-id",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-NOMASTER",
                clusterName = "test-spark",
                masterPublicDns = null,
                state = "STARTING",
            )

        whenever(mockEmrService.createCluster(any())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-NOMASTER"))
            .thenThrow(IllegalStateException("Cluster failed"))
        whenever(mockEmrService.listInstances(eq("j-NOMASTER"), any()))
            .thenReturn(emptyList())

        assertThatThrownBy {
            service.provisionEmrCluster(
                clusterName = "test",
                masterInstanceType = "m5.xlarge",
                workerInstanceType = "m5.xlarge",
                workerCount = 3,
                subnetId = "subnet-123",
                securityGroupId = "sg-456",
                keyName = "my-key",
                clusterState = clusterState,
                tags = emptyMap(),
            )
        }.isInstanceOf(IllegalStateException::class.java)

        val unavailableEvents = capturedEvents.filterIsInstance<Event.Emr.BootstrapFailureLogUnavailable>()
        assertThat(unavailableEvents).hasSize(1)
        assertThat(unavailableEvents.first().reason).isEqualTo("No master instance found")

        // Should also emit debug instructions as fallback
        assertThat(capturedEvents).anyMatch { it is Event.Emr.BootstrapFailureDebugInstructions }
    }

    @Test
    fun `debug instructions should contain cluster ID and S3 path`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
                clusterId = "test-id",
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            )

        val createResult =
            EMRClusterResult(
                clusterId = "j-DIAG",
                clusterName = "test-spark",
                masterPublicDns = null,
                state = "STARTING",
            )

        whenever(mockEmrService.createCluster(any())).thenReturn(createResult)
        whenever(mockEmrService.waitForClusterReady("j-DIAG"))
            .thenThrow(IllegalStateException("Cluster failed"))
        whenever(mockEmrService.listInstances(eq("j-DIAG"), any()))
            .thenReturn(emptyList())

        assertThatThrownBy {
            service.provisionEmrCluster(
                clusterName = "test",
                masterInstanceType = "m5.xlarge",
                workerInstanceType = "m5.xlarge",
                workerCount = 3,
                subnetId = "subnet-123",
                securityGroupId = "sg-456",
                keyName = "my-key",
                clusterState = clusterState,
                tags = emptyMap(),
            )
        }.isInstanceOf(IllegalStateException::class.java)

        val debugEvents = capturedEvents.filterIsInstance<Event.Emr.BootstrapFailureDebugInstructions>()
        assertThat(debugEvents).hasSize(1)
        assertThat(debugEvents.first().clusterId).isEqualTo("j-DIAG")
        assertThat(debugEvents.first().emrLogsPath)
            .isEqualTo("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/spark/emr-logs")
    }
}
