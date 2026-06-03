package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.clickhouse.ClickHouseService
import com.rustyrazorblade.easydblab.services.sql.SqlQueryResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for [ClickHouseSql] command.
 */
class ClickHouseSqlTest : BaseKoinTest() {
    private lateinit var mockClickHouseService: ClickHouseService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClickHouseService> { mockClickHouseService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClickHouseService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `inline sql executes query and emits output`() {
        whenever(mockClickHouseService.execute(any()))
            .thenReturn(Result.success(SqlQueryResult(listOf("count"), listOf(listOf("99")))))

        val command = ClickHouseSql()
        command.statement = "SELECT count(*) FROM default.events"
        command.execute()

        verify(mockClickHouseService).execute("SELECT count(*) FROM default.events")
        assertThat(outputHandler.messages.joinToString("\n")).contains("99")
    }

    @Test
    fun `trailing semicolon is stripped`() {
        whenever(mockClickHouseService.execute(any())).thenReturn(Result.success(SqlQueryResult(emptyList(), emptyList())))

        val command = ClickHouseSql()
        command.statement = "SELECT 1;"
        command.execute()

        verify(mockClickHouseService).execute("SELECT 1")
    }

    @Test
    fun `file sql reads file and executes query`() {
        val sqlFile = File(tempDir, "query.sql")
        sqlFile.writeText("SELECT version()")
        whenever(mockClickHouseService.execute(any()))
            .thenReturn(Result.success(SqlQueryResult(listOf("version()"), listOf(listOf("24.8")))))

        val command = ClickHouseSql()
        command.file = sqlFile
        command.execute()

        verify(mockClickHouseService).execute("SELECT version()")
    }

    @Test
    fun `missing file emits error`() {
        val command = ClickHouseSql()
        command.file = File("/nonexistent/query.sql")
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("File not found")
    }

    @Test
    fun `no sql provided does not call the service`() {
        val command = ClickHouseSql()
        command.execute()

        // Usage text is printed directly via println(); verify the service was never invoked
        org.mockito.kotlin.verifyNoInteractions(mockClickHouseService)
    }

    @Test
    fun `service failure emits error`() {
        whenever(mockClickHouseService.execute(any()))
            .thenReturn(Result.failure(RuntimeException("Table not found")))

        val command = ClickHouseSql()
        command.statement = "SELECT * FROM missing"
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("Table not found")
    }
}
