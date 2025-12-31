package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.providers.aws.EMRSparkService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.AddJobFlowStepsRequest
import software.amazon.awssdk.services.emr.model.AddJobFlowStepsResponse
import software.amazon.awssdk.services.emr.model.DescribeStepRequest
import software.amazon.awssdk.services.emr.model.DescribeStepResponse
import software.amazon.awssdk.services.emr.model.EmrException
import software.amazon.awssdk.services.emr.model.FailureDetails
import software.amazon.awssdk.services.emr.model.Step
import software.amazon.awssdk.services.emr.model.StepState
import software.amazon.awssdk.services.emr.model.StepStateChangeReason
import software.amazon.awssdk.services.emr.model.StepStatus

/**
 * Test suite for EMRSparkService following TDD principles.
 *
 * These tests verify Spark job lifecycle operations (submit, status, wait)
 * and cluster validation using mocked EMR client.
 */
class EMRSparkServiceTest : BaseKoinTest() {
    private lateinit var mockEmrClient: EmrClient
    private lateinit var mockObjectStore: ObjectStore
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockVictoriaLogsService: VictoriaLogsService
    private lateinit var sparkService: SparkService

    private val testClusterId = "j-ABC123DEF456"
    private val testStepId = "s-XYZ789GHI012"
    private val testJarPath = "s3://test-bucket/clusters/cluster-123/spark-jars/test.jar"
    private val testMainClass = "com.example.Main"

    private val validClusterInfo =
        EMRClusterInfo(
            clusterId = testClusterId,
            name = "test-cluster",
            masterPublicDns = "master.example.com",
            state = "WAITING",
        )

    private val validEmrClusterState =
        EMRClusterState(
            clusterId = testClusterId,
            clusterName = "test-cluster",
            masterPublicDns = "master.example.com",
            state = "WAITING",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<EmrClient> { mockEmrClient }
                single<ObjectStore> { mockObjectStore }
                single<ClusterStateManager> { mockClusterStateManager }
                single<VictoriaLogsService> { mockVictoriaLogsService }
                factory<SparkService> { EMRSparkService(get(), get(), get(), get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockEmrClient = mock()
        mockObjectStore = mock()
        mockClusterStateManager = mock()
        mockVictoriaLogsService = mock()
        sparkService = getKoin().get()
    }

    // ========== VALIDATE CLUSTER TESTS ==========

    @Test
    fun `validateCluster should return success when cluster exists and is in WAITING state`() {
        // Given
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                emrCluster = validEmrClusterState,
            )
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        // When
        val result = sparkService.validateCluster()

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(validClusterInfo)
    }

    @Test
    fun `validateCluster should return success when cluster exists and is in RUNNING state`() {
        // Given
        val runningEmrClusterState = validEmrClusterState.copy(state = "RUNNING")
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                emrCluster = runningEmrClusterState,
            )
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        // When
        val result = sparkService.validateCluster()

        // Then
        assertThat(result.isSuccess).isTrue()
        val runningClusterInfo = validClusterInfo.copy(state = "RUNNING")
        assertThat(result.getOrNull()).isEqualTo(runningClusterInfo)
    }

    @Test
    fun `validateCluster should return failure when cluster does not exist`() {
        // Given
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                emrCluster = null,
            )
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        // When
        val result = sparkService.validateCluster()

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `validateCluster should return failure when cluster is in invalid state`() {
        // Given
        val terminatingEmrClusterState = validEmrClusterState.copy(state = "TERMINATING")
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                emrCluster = terminatingEmrClusterState,
            )
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        // When
        val result = sparkService.validateCluster()

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("TERMINATING")
            .hasMessageContaining("WAITING")
    }

    // ========== SUBMIT JOB TESTS ==========

    @Test
    fun `submitJob should submit Spark job successfully and return step ID`() {
        // Given
        val jobArgs = listOf("arg1", "arg2")
        val jobName = "Test Job"

        val response =
            AddJobFlowStepsResponse
                .builder()
                .stepIds(testStepId)
                .build()

        whenever(mockEmrClient.addJobFlowSteps(any<AddJobFlowStepsRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.submitJob(testClusterId, testJarPath, testMainClass, jobArgs, jobName)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(testStepId)
        verify(mockEmrClient).addJobFlowSteps(any<AddJobFlowStepsRequest>())
    }

    @Test
    fun `submitJob should use main class name as job name when jobName is null`() {
        // Given
        val response =
            AddJobFlowStepsResponse
                .builder()
                .stepIds(testStepId)
                .build()

        whenever(mockEmrClient.addJobFlowSteps(any<AddJobFlowStepsRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.submitJob(testClusterId, testJarPath, testMainClass, listOf(), null)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(testStepId)
    }

    @Test
    fun `submitJob should include job arguments in spark-submit command`() {
        // Given
        val jobArgs = listOf("--input", "data.csv", "--output", "results.txt")

        val response =
            AddJobFlowStepsResponse
                .builder()
                .stepIds(testStepId)
                .build()

        whenever(mockEmrClient.addJobFlowSteps(any<AddJobFlowStepsRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.submitJob(testClusterId, testJarPath, testMainClass, jobArgs, null)

        // Then
        assertThat(result.isSuccess).isTrue()
    }

    // ========== GET JOB STATUS TESTS ==========

    @Test
    fun `getJobStatus should return job status successfully`() {
        // Given
        val stepStatus =
            StepStatus
                .builder()
                .state(StepState.RUNNING)
                .build()

        val step =
            Step
                .builder()
                .status(stepStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.getJobStatus(testClusterId, testStepId)

        // Then
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()
        assertThat(status).isNotNull
        assertThat(status!!.state).isEqualTo(StepState.RUNNING)
    }

    @Test
    fun `getJobStatus should include state change reason when present`() {
        // Given
        val stateChangeReason =
            StepStateChangeReason
                .builder()
                .message("Step completed successfully")
                .build()

        val stepStatus =
            StepStatus
                .builder()
                .state(StepState.COMPLETED)
                .stateChangeReason(stateChangeReason)
                .build()

        val step =
            Step
                .builder()
                .status(stepStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.getJobStatus(testClusterId, testStepId)

        // Then
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()
        assertThat(status).isNotNull
        assertThat(status!!.stateChangeReason).isEqualTo("Step completed successfully")
    }

    @Test
    fun `getJobStatus should include failure details when job fails`() {
        // Given
        val failureDetails =
            FailureDetails
                .builder()
                .message("Out of memory error")
                .build()

        val stepStatus =
            StepStatus
                .builder()
                .state(StepState.FAILED)
                .failureDetails(failureDetails)
                .build()

        val step =
            Step
                .builder()
                .status(stepStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.getJobStatus(testClusterId, testStepId)

        // Then
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()
        assertThat(status).isNotNull
        assertThat(status!!.state).isEqualTo(StepState.FAILED)
        assertThat(status.failureDetails).isEqualTo("Out of memory error")
    }

    // ========== WAIT FOR JOB COMPLETION TESTS ==========

    @Test
    fun `waitForJobCompletion should return success when job completes successfully`() {
        // Given
        val completedStatus =
            StepStatus
                .builder()
                .state(StepState.COMPLETED)
                .build()

        val step =
            Step
                .builder()
                .status(completedStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.waitForJobCompletion(testClusterId, testStepId)

        // Then
        assertThat(result.isSuccess).isTrue()
        val status = result.getOrNull()
        assertThat(status).isNotNull
        assertThat(status!!.state).isEqualTo(StepState.COMPLETED)
    }

    @Test
    fun `waitForJobCompletion should return failure when job fails`() {
        // Given
        val failedStatus =
            StepStatus
                .builder()
                .state(StepState.FAILED)
                .failureDetails(
                    FailureDetails
                        .builder()
                        .message("Application error")
                        .build(),
                ).build()

        val step =
            Step
                .builder()
                .status(failedStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // Mock cluster state for S3 bucket lookup
        val clusterState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "test-bucket",
            )
        whenever(mockClusterStateManager.load()).thenReturn(clusterState)

        // Mock Victoria Logs query (returns empty logs)
        whenever(mockVictoriaLogsService.query(any(), any(), any())).thenReturn(Result.success(emptyList()))

        // When
        val result = sparkService.waitForJobCompletion(testClusterId, testStepId)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Job failed")
    }

    @Test
    fun `waitForJobCompletion should return failure when job is cancelled`() {
        // Given
        val cancelledStatus =
            StepStatus
                .builder()
                .state(StepState.CANCELLED)
                .build()

        val step =
            Step
                .builder()
                .status(cancelledStatus)
                .build()

        val response =
            DescribeStepResponse
                .builder()
                .step(step)
                .build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(response)

        // When
        val result = sparkService.waitForJobCompletion(testClusterId, testStepId)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Job was cancelled")
    }

    // ========== MULTI-STATE POLLING TESTS ==========

    @Test
    fun `waitForJobCompletion should poll through multiple states before completion`() {
        // Given - simulate PENDING -> RUNNING -> COMPLETED transitions
        val pendingStatus = StepStatus.builder().state(StepState.PENDING).build()
        val runningStatus = StepStatus.builder().state(StepState.RUNNING).build()
        val completedStatus = StepStatus.builder().state(StepState.COMPLETED).build()

        val pendingStep = Step.builder().status(pendingStatus).build()
        val runningStep = Step.builder().status(runningStatus).build()
        val completedStep = Step.builder().status(completedStatus).build()

        val pendingResponse = DescribeStepResponse.builder().step(pendingStep).build()
        val runningResponse = DescribeStepResponse.builder().step(runningStep).build()
        val completedResponse = DescribeStepResponse.builder().step(completedStep).build()

        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenReturn(pendingResponse)
            .thenReturn(runningResponse)
            .thenReturn(completedResponse)

        // When
        val result = sparkService.waitForJobCompletion(testClusterId, testStepId)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.state).isEqualTo(StepState.COMPLETED)
    }

    // ========== EMR API ERROR TESTS ==========

    @Test
    fun `submitJob should return failure when EMR API throws exception`() {
        // Given
        whenever(mockEmrClient.addJobFlowSteps(any<AddJobFlowStepsRequest>()))
            .thenThrow(
                EmrException
                    .builder()
                    .message("EMR service unavailable")
                    .statusCode(503)
                    .build(),
            )

        // When
        val result = sparkService.submitJob(testClusterId, testJarPath, testMainClass, listOf(), null)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(EmrException::class.java)
    }

    @Test
    fun `getJobStatus should return failure when EMR API throws exception`() {
        // Given
        whenever(mockEmrClient.describeStep(any<DescribeStepRequest>()))
            .thenThrow(
                EmrException
                    .builder()
                    .message("Step not found")
                    .statusCode(404)
                    .build(),
            )

        // When
        val result = sparkService.getJobStatus(testClusterId, testStepId)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(EmrException::class.java)
    }
}
