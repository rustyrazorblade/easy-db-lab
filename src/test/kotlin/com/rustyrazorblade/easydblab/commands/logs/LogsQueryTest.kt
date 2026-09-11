package com.rustyrazorblade.easydblab.commands.logs

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LogsQueryTest : BaseKoinTest() {
    private lateinit var mockVictoriaLogsService: VictoriaLogsService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<VictoriaLogsService> { mockVictoriaLogsService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockVictoriaLogsService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        // Local-mode cluster by default: the redirect guard passes, so query building runs.
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), initConfig = InitConfig(region = "us-west-2")),
        )
    }

    @Test
    fun `execute refuses on a telemetry-redirect cluster`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2", telemetryRedirect = TelemetryRedirect.fromBaseHost("10.0.0.9")),
            ),
        )

        val command = LogsQuery()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("telemetry-redirect")

        verify(mockVictoriaLogsService, never()).query(any(), any(), any())
    }

    @Nested
    inner class QueryBuilding {
        @Test
        fun `execute builds wildcard query with no filters`() {
            whenever(mockVictoriaLogsService.query(eq("*"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.execute()

            verify(mockVictoriaLogsService).query(eq("*"), eq("1h"), eq(100))
        }

        @Test
        fun `execute builds query with source filter`() {
            whenever(mockVictoriaLogsService.query(eq("source:cassandra"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.source = "cassandra"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("source:cassandra"), any(), any())
        }

        @Test
        fun `execute builds query with host filter`() {
            whenever(mockVictoriaLogsService.query(eq("host:db0"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.host = "db0"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("host:db0"), any(), any())
        }

        @Test
        fun `execute builds query with multiple filters`() {
            whenever(mockVictoriaLogsService.query(eq("source:cassandra AND host:db0"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.source = "cassandra"
            command.host = "db0"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("source:cassandra AND host:db0"), any(), any())
        }

        @Test
        fun `execute builds query with unit filter`() {
            whenever(mockVictoriaLogsService.query(eq("unit:docker.service"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.unit = "docker.service"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("unit:docker.service"), any(), any())
        }

        @Test
        fun `execute builds query with grep filter`() {
            whenever(mockVictoriaLogsService.query(eq("\"OutOfMemory\""), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.grep = "OutOfMemory"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("\"OutOfMemory\""), any(), any())
        }

        @Test
        fun `execute uses raw query when provided`() {
            whenever(mockVictoriaLogsService.query(eq("source:cassandra AND host:db0"), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.rawQuery = "source:cassandra AND host:db0"
            command.execute()

            verify(mockVictoriaLogsService).query(eq("source:cassandra AND host:db0"), any(), any())
        }
    }

    @Nested
    inner class QueryOptions {
        @Test
        fun `execute uses custom time range`() {
            whenever(mockVictoriaLogsService.query(any(), eq("30m"), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.since = "30m"
            command.execute()

            verify(mockVictoriaLogsService).query(any(), eq("30m"), any())
        }

        @Test
        fun `execute uses custom limit`() {
            whenever(mockVictoriaLogsService.query(any(), any(), eq(500)))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.limit = 500
            command.execute()

            verify(mockVictoriaLogsService).query(any(), any(), eq(500))
        }
    }

    @Nested
    inner class Results {
        @Test
        fun `execute displays no logs found message`() {
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(emptyList()))

            val command = LogsQuery()
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("No logs found")
        }

        @Test
        fun `execute displays log entries`() {
            val logs = listOf("2024-01-01 INFO Starting", "2024-01-01 INFO Started")
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.success(logs))

            val command = LogsQuery()
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("2024-01-01 INFO Starting")
            assertThat(output).contains("Found 2 log entries")
        }

        @Test
        fun `execute displays error on query failure`() {
            whenever(mockVictoriaLogsService.query(any(), any(), any()))
                .thenReturn(Result.failure(RuntimeException("Connection refused")))

            val command = LogsQuery()
            command.execute()

            val errors = outputHandler.errors.joinToString("\n") { it.first }
            assertThat(errors).contains("Connection refused")
            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Tips")
        }
    }
}
