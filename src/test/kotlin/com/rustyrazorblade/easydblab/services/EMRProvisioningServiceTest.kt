package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterResult
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for EMRProvisioningService verifying correct EMR cluster creation.
 */
internal class EMRProvisioningServiceTest {
    private lateinit var mockEmrService: EMRService
    private lateinit var mockOutputHandler: OutputHandler
    private lateinit var service: DefaultEMRProvisioningService

    @BeforeEach
    fun setUp() {
        mockEmrService = mock()
        mockOutputHandler = mock()
        service = DefaultEMRProvisioningService(mockEmrService, mockOutputHandler)
    }

    @Test
    fun `provisionEmrCluster should create cluster with correct config`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
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
    }

    @Test
    fun `provisionEmrCluster should call createCluster then waitForClusterReady`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
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
    fun `provisionEmrCluster should propagate errors from waitForClusterReady`() {
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test-bucket",
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
    }
}
