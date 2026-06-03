package com.rustyrazorblade.easydblab.commands.presto

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.presto.PrestoQueryResult
import com.rustyrazorblade.easydblab.services.presto.PrestoService
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
 * Tests for [PrestoSql] command. Verifies routing to [PrestoService] and correct
 * event emission for all input/output scenarios.
 */
class PrestoSqlTest : BaseKoinTest() {
    private lateinit var mockPrestoService: PrestoService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<PrestoService> { mockPrestoService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockPrestoService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `inline sql executes query and emits output`() {
        whenever(mockPrestoService.execute(any()))
            .thenReturn(Result.success(PrestoQueryResult(listOf("count"), listOf(listOf("42")))))

        val command = PrestoSql()
        command.statement = "SELECT count(*) FROM t"
        command.execute()

        verify(mockPrestoService).execute("SELECT count(*) FROM t")
        assertThat(outputHandler.messages.joinToString("\n")).contains("42")
    }

    @Test
    fun `trailing semicolon is stripped before execution`() {
        whenever(mockPrestoService.execute(any())).thenReturn(Result.success(PrestoQueryResult(emptyList(), emptyList())))

        val command = PrestoSql()
        command.statement = "SELECT 1;"
        command.execute()

        verify(mockPrestoService).execute("SELECT 1")
    }

    @Test
    fun `file sql reads file and executes query`() {
        val sqlFile = File(tempDir, "query.sql")
        sqlFile.writeText("SELECT id FROM t")

        whenever(mockPrestoService.execute(any()))
            .thenReturn(Result.success(PrestoQueryResult(listOf("id"), listOf(listOf("1")))))

        val command = PrestoSql()
        command.file = sqlFile
        command.execute()

        verify(mockPrestoService).execute("SELECT id FROM t")
    }

    @Test
    fun `missing file emits error`() {
        val command = PrestoSql()
        command.file = File("/nonexistent/query.sql")
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("File not found")
    }

    @Test
    fun `no sql provided does not call the service`() {
        val command = PrestoSql()
        command.execute()

        // Usage text is printed directly via println(); verify the service was never invoked
        org.mockito.kotlin.verifyNoInteractions(mockPrestoService)
    }

    @Test
    fun `service failure emits error`() {
        whenever(mockPrestoService.execute(any()))
            .thenReturn(Result.failure(RuntimeException("Table not found")))

        val command = PrestoSql()
        command.statement = "SELECT * FROM missing"
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("Table not found")
    }

    @Test
    fun `empty result set is displayed with row count`() {
        whenever(mockPrestoService.execute(any()))
            .thenReturn(Result.success(PrestoQueryResult(emptyList(), emptyList())))

        val command = PrestoSql()
        command.statement = "SELECT 1 WHERE false"
        command.execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("0 rows")
    }
}
