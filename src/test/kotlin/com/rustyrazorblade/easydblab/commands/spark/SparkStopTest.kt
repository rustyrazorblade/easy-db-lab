package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.services.SparkService
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

class SparkStopTest : BaseKoinTest() {
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
    fun `execute should fail when cluster validation fails`() {
        initMocks()

        val command = SparkStop()

        whenever(mockSparkService.validateCluster())
            .thenReturn(Result.failure(IllegalStateException("No EMR cluster found")))

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `execute with explicit step-id should cancel that step`() {
        initMocks()

        val command = SparkStop()
        command.stepId = "s-EXPLICIT123"

        val cancelResult =
            SparkService.CancelJobResult(
                stepId = "s-EXPLICIT123",
                status = "SUBMITTED",
            )

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.cancelJob(eq("j-TEST123"), eq("s-EXPLICIT123")))
            .thenReturn(Result.success(cancelResult))

        command.execute()

        verify(mockSparkService, never()).listJobs(any(), any())
        verify(mockSparkService).cancelJob(eq("j-TEST123"), eq("s-EXPLICIT123"))
    }

    @Test
    fun `execute without step-id should cancel most recent job`() {
        initMocks()

        val command = SparkStop()
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

        val cancelResult =
            SparkService.CancelJobResult(
                stepId = "s-RECENT123",
                status = "SUBMITTED",
            )

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(eq("j-TEST123"), eq(1))).thenReturn(Result.success(jobs))
        whenever(mockSparkService.cancelJob(eq("j-TEST123"), eq("s-RECENT123")))
            .thenReturn(Result.success(cancelResult))

        command.execute()

        verify(mockSparkService).listJobs(eq("j-TEST123"), eq(1))
        verify(mockSparkService).cancelJob(eq("j-TEST123"), eq("s-RECENT123"))
    }

    @Test
    fun `execute without step-id should fail when no jobs exist`() {
        initMocks()

        val command = SparkStop()
        command.stepId = null

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.listJobs(eq("j-TEST123"), eq(1))).thenReturn(Result.success(emptyList()))

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No jobs found")
    }

    @Test
    fun `execute should fail when cancelJob fails`() {
        initMocks()

        val command = SparkStop()
        command.stepId = "s-STEP123"

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.cancelJob(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Cancel failed")))

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cancel failed")
    }
}
