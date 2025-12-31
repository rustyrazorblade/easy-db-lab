package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.spark.SparkStatus
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.services.SparkService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.emr.model.StepState
import java.time.Instant

class SparkStatusTest : BaseKoinTest() {
    private lateinit var mockSparkService: SparkService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<SparkService>().also {
                        mockSparkService = it
                    }
                }
            },
        )

    // Helper to initialize mocks by triggering Koin injection
    private fun initMocks() {
        get<SparkService>()
    }

    private val validClusterInfo =
        EMRClusterInfo(
            clusterId = "j-TEST123",
            name = "test-cluster",
            masterPublicDns = "master.example.com",
            state = "WAITING",
        )

    @Test
    fun `command has sensible defaults`() {
        val command = SparkStatus()
        assertThat(command.stepId).isNull()
    }

    @Test
    fun `execute should fail when cluster validation fails`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkStatus()

        whenever(mockSparkService.validateCluster())
            .thenReturn(Result.failure(IllegalStateException("No EMR cluster found")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `execute with explicit step-id should check status of that step`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkStatus()
        command.stepId = "s-EXPLICIT123"

        val jobStatus =
            SparkService.JobStatus(
                state = StepState.COMPLETED,
                stateChangeReason = null,
                failureDetails = null,
            )

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.getJobStatus(eq("j-TEST123"), eq("s-EXPLICIT123")))
            .thenReturn(Result.success(jobStatus))

        // When
        command.execute()

        // Then - should NOT call listJobs since step-id was explicit
        verify(mockSparkService, never()).listJobs(any(), any())
        verify(mockSparkService).getJobStatus(eq("j-TEST123"), eq("s-EXPLICIT123"))
    }

    @Test
    fun `execute without step-id should get most recent job`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkStatus()
        command.stepId = null

        val jobs =
            listOf(
                SparkService.JobInfo(
                    stepId = "s-RECENT123",
                    name = "Most Recent Job",
                    state = StepState.RUNNING,
                    startTime = Instant.now(),
                ),
            )

        val jobStatus =
            SparkService.JobStatus(
                state = StepState.RUNNING,
                stateChangeReason = null,
                failureDetails = null,
            )

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(eq("j-TEST123"), eq(1))).thenReturn(Result.success(jobs))
        whenever(mockSparkService.getJobStatus(eq("j-TEST123"), eq("s-RECENT123")))
            .thenReturn(Result.success(jobStatus))

        // When
        command.execute()

        // Then - should call listJobs to get most recent
        verify(mockSparkService).listJobs(eq("j-TEST123"), eq(1))
        verify(mockSparkService).getJobStatus(eq("j-TEST123"), eq("s-RECENT123"))
    }

    @Test
    fun `execute without step-id should fail when no jobs exist`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkStatus()
        command.stepId = null

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(eq("j-TEST123"), eq(1))).thenReturn(Result.success(emptyList()))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No jobs found")
    }

    @Test
    fun `execute should fail when getJobStatus fails`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkStatus()
        command.stepId = "s-STEP123"

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.getJobStatus(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Step not found")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Step not found")
    }
}
