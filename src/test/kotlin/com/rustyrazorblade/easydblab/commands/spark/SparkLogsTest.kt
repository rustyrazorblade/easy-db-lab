package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.emr.model.StepState
import java.time.Instant

class SparkLogsTest : BaseKoinTest() {
    private lateinit var mockSparkService: SparkService
    private lateinit var mockVictoriaLogsService: VictoriaLogsService
    private lateinit var outputHandler: BufferedOutputHandler

    private val testClusterInfo =
        EMRClusterInfo(
            clusterId = "j-TESTCLUSTER",
            name = "test-emr",
            masterPublicDns = "ec2-1-2-3-4.compute-1.amazonaws.com",
            state = "WAITING",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<SparkService> { mockSparkService }
                single<VictoriaLogsService> { mockVictoriaLogsService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockSparkService = mock()
        mockVictoriaLogsService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(testClusterInfo))
    }

    @Nested
    inner class Validation {
        @Test
        fun `execute fails when cluster validation fails`() {
            whenever(mockSparkService.validateCluster())
                .thenReturn(Result.failure(RuntimeException("No EMR cluster found")))

            val command = SparkLogs()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No EMR cluster found")
        }

        @Test
        fun `execute fails when no jobs found`() {
            whenever(mockSparkService.listJobs(eq("j-TESTCLUSTER"), any()))
                .thenReturn(Result.success(emptyList()))

            val command = SparkLogs()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No jobs found")
        }
    }

    @Nested
    inner class SuccessfulQuery {
        @Test
        fun `execute queries logs with provided step ID`() {
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(listOf("log line 1")))

            val command = SparkLogs()
            command.stepId = "s-TESTSTEP"
            command.execute()

            verify(mockVictoriaLogsService).query(
                eq("source:emr AND \"s-TESTSTEP\""),
                eq("1d"),
                eq(100),
            )
        }

        @Test
        fun `execute uses most recent step ID when not provided`() {
            val job =
                SparkService.JobInfo(
                    stepId = "s-RECENT",
                    name = "recent-job",
                    state = StepState.COMPLETED,
                    startTime = Instant.now(),
                )
            whenever(mockSparkService.listJobs(eq("j-TESTCLUSTER"), eq(1)))
                .thenReturn(Result.success(listOf(job)))
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(listOf("log line")))

            val command = SparkLogs()
            command.execute()

            verify(mockVictoriaLogsService).query(
                eq("source:emr AND \"s-RECENT\""),
                any(),
                any(),
            )
        }

        @Test
        fun `execute displays log entries`() {
            val logs = listOf("2024-01-01 Step started", "2024-01-01 Step completed")
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(logs))

            val command = SparkLogs()
            command.stepId = "s-TEST"
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Step started")
            assertThat(output).contains("Found 2 log entries")
        }

        @Test
        fun `execute shows no logs message with tips`() {
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = SparkLogs()
            command.stepId = "s-TEST"
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No logs found")
            assertThat(output).contains("Tips")
        }

        @Test
        fun `execute uses custom limit and time range`() {
            whenever(mockVictoriaLogsService.query(any(), eq("30m"), eq(500)))
                .thenReturn(Result.success(emptyList()))

            val command = SparkLogs()
            command.stepId = "s-TEST"
            command.since = "30m"
            command.limit = 500
            command.execute()

            verify(mockVictoriaLogsService).query(any(), eq("30m"), eq(500))
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `execute handles query failure`() {
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.failure(RuntimeException("Connection refused")))

            val command = SparkLogs()
            command.stepId = "s-TEST"
            command.execute()

            val errors = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errors).contains("Connection refused")
            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Tips")
        }
    }
}
