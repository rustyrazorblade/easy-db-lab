package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.spark.SparkJobs
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.services.SparkJobState
import com.rustyrazorblade.easydblab.services.SparkService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class SparkJobsTest : BaseKoinTest() {
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
    fun `command has default limit of 10`() {
        val command = SparkJobs()
        assertThat(command.limit).isEqualTo(SparkService.DEFAULT_JOB_LIST_LIMIT)
    }

    @Test
    fun `execute should fail when cluster validation fails`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkJobs()

        whenever(mockSparkService.validateCluster())
            .thenReturn(Result.failure(IllegalStateException("No EMR cluster found")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `execute should succeed when cluster is valid and jobs exist`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkJobs()

        val jobs =
            listOf(
                SparkService.JobInfo(
                    stepId = "s-STEP1",
                    name = "Test Job 1",
                    state = SparkJobState.COMPLETED,
                    startTime = Instant.now(),
                ),
                SparkService.JobInfo(
                    stepId = "s-STEP2",
                    name = "Test Job 2",
                    state = SparkJobState.RUNNING,
                    startTime = Instant.now(),
                ),
            )

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(any(), any())).thenReturn(Result.success(jobs))

        // When - should not throw
        command.execute()
    }

    @Test
    fun `execute should succeed when no jobs exist`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkJobs()

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(any(), any())).thenReturn(Result.success(emptyList()))

        // When - should not throw
        command.execute()
    }

    @Test
    fun `execute should fail when listJobs fails`() {
        // Initialize mocks before use
        initMocks()

        // Given
        val command = SparkJobs()

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(any(), any()))
            .thenReturn(Result.failure(RuntimeException("EMR API error")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("EMR API error")
    }
}
