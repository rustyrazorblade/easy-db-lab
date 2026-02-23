package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CqlSessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class CqlTest : BaseKoinTest() {
    private lateinit var mockCqlSessionService: CqlSessionService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CqlSessionService> { mockCqlSessionService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockCqlSessionService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `execute runs CQL statement and outputs result`() {
        whenever(mockCqlSessionService.execute(eq("SELECT * FROM system.local")))
            .thenReturn(Result.success("cluster_name\nTest Cluster"))

        val command = Cql()
        command.statement = "SELECT * FROM system.local"
        command.execute()

        verify(mockCqlSessionService).execute("SELECT * FROM system.local")
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Test Cluster")
    }

    @Test
    fun `execute trims trailing semicolon`() {
        whenever(mockCqlSessionService.execute(eq("SELECT * FROM system.local")))
            .thenReturn(Result.success("ok"))

        val command = Cql()
        command.statement = "SELECT * FROM system.local;"
        command.execute()

        verify(mockCqlSessionService).execute("SELECT * FROM system.local")
    }

    @Test
    fun `execute outputs OK for blank result`() {
        whenever(mockCqlSessionService.execute(eq("CREATE KEYSPACE test")))
            .thenReturn(Result.success(""))

        val command = Cql()
        command.statement = "CREATE KEYSPACE test"
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("OK")
    }

    @Test
    fun `execute outputs error message on failure`() {
        whenever(mockCqlSessionService.execute(eq("bad query")))
            .thenReturn(Result.failure(RuntimeException("Syntax error")))

        val command = Cql()
        command.statement = "bad query"
        command.execute()

        val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errorOutput).contains("Syntax error")
    }

    @Test
    fun `execute shows usage when no statement or file provided`() {
        val command = Cql()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Usage:")
    }

    @Test
    fun `execute reads CQL from file`() {
        val cqlFile = File(tempDir, "test.cql")
        cqlFile.writeText("SELECT * FROM system.peers")

        whenever(mockCqlSessionService.execute(eq("SELECT * FROM system.peers")))
            .thenReturn(Result.success("peer\n10.0.1.1"))

        val command = Cql()
        command.file = cqlFile
        command.execute()

        verify(mockCqlSessionService).execute("SELECT * FROM system.peers")
    }

    @Test
    fun `execute handles missing file`() {
        val command = Cql()
        command.file = File("/nonexistent/file.cql")
        command.execute()

        val errorOutput = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errorOutput).contains("File not found")
    }
}
