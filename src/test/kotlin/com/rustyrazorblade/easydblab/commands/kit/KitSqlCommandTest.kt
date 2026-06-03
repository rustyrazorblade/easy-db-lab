package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.sql.JdbcConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

/**
 * Tests for [KitSqlCommand]. Verifies SQL routing, file handling, semicolon stripping,
 * driver loading, and event emission for all input/output scenarios.
 */
class KitSqlCommandTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val appHost =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.1.10",
            alias = "app0",
            availabilityZone = "us-west-2a",
        )

    private val jdbcEndpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "app",
            port = 8080,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "presto",
            path = "/cassandra",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Stress to listOf(appHost)),
            ),
        )
    }

    private fun mockSuccessConnection(
        columns: List<String>,
        rows: List<List<String>>,
    ): JdbcConnectionFactory {
        val meta = mock<ResultSetMetaData>()
        whenever(meta.columnCount).thenReturn(columns.size)
        columns.forEachIndexed { i, name -> whenever(meta.getColumnName(i + 1)).thenReturn(name) }

        val rs = mock<ResultSet>()
        whenever(rs.metaData).thenReturn(meta)
        var currentRow = listOf<String>()
        val iterator = rows.iterator()
        whenever(rs.next()).thenAnswer {
            if (iterator.hasNext()) {
                currentRow = iterator.next()
                true
            } else {
                false
            }
        }
        whenever(rs.getString(org.mockito.kotlin.any<Int>())).thenAnswer { inv ->
            currentRow[inv.getArgument<Int>(0) - 1]
        }

        val stmt = mock<Statement>()
        whenever(stmt.executeQuery(org.mockito.kotlin.any())).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)
        return JdbcConnectionFactory { _, _ -> conn }
    }

    private fun buildCommand(
        connectionFactory: JdbcConnectionFactory = JdbcConnectionFactory { _, _ -> mock() },
        driverClass: String = "",
    ) = KitSqlCommand(
        kitName = "presto",
        endpoint = jdbcEndpoint,
        user = "easy-db-lab",
        driverClass = driverClass,
        connectionFactory = connectionFactory,
    )

    @Test
    fun `inline sql executes query and emits output`() {
        val factory = mockSuccessConnection(listOf("count"), listOf(listOf("42")))
        val command = buildCommand(connectionFactory = factory)
        command.statement = "SELECT count(*) FROM t"
        command.execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("42")
    }

    @Test
    fun `trailing semicolon is stripped before execution`() {
        var executedSql: String? = null
        val factory =
            JdbcConnectionFactory { _, _ ->
                val stmt = mock<Statement>()
                whenever(stmt.executeQuery(org.mockito.kotlin.any())).thenAnswer { inv ->
                    executedSql = inv.getArgument(0)
                    val meta = mock<ResultSetMetaData>()
                    whenever(meta.columnCount).thenReturn(0)
                    val rs = mock<ResultSet>()
                    whenever(rs.metaData).thenReturn(meta)
                    whenever(rs.next()).thenReturn(false)
                    rs
                }
                val conn = mock<Connection>()
                whenever(conn.createStatement()).thenReturn(stmt)
                conn
            }

        val command = buildCommand(connectionFactory = factory)
        command.statement = "SELECT 1;"
        command.execute()

        assertThat(executedSql).isEqualTo("SELECT 1")
    }

    @Test
    fun `file sql reads file and executes query`() {
        val sqlFile = File(tempDir, "query.sql")
        sqlFile.writeText("SELECT id FROM t")
        val factory = mockSuccessConnection(listOf("id"), listOf(listOf("1")))

        val command = buildCommand(connectionFactory = factory)
        command.file = sqlFile
        command.execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("1")
    }

    @Test
    fun `missing file emits error`() {
        val command = buildCommand()
        command.file = File("/nonexistent/query.sql")
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("File not found")
    }

    @Test
    fun `no sql provided does not attempt connection`() {
        var connectionAttempted = false
        val factory =
            JdbcConnectionFactory { _, _ ->
                connectionAttempted = true
                mock()
            }

        val command = buildCommand(connectionFactory = factory)
        command.execute()

        assertThat(connectionAttempted).isFalse()
    }

    @Test
    fun `service failure emits error`() {
        val factory = JdbcConnectionFactory { _, _ -> throw RuntimeException("Table not found") }

        val command = buildCommand(connectionFactory = factory)
        command.statement = "SELECT * FROM missing"
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("Table not found")
    }

    @Test
    fun `unknown driver class emits error and skips execution`() {
        var connectionAttempted = false
        val factory =
            JdbcConnectionFactory { _, _ ->
                connectionAttempted = true
                mock()
            }

        val command = buildCommand(connectionFactory = factory, driverClass = "com.nonexistent.Driver")
        command.statement = "SELECT 1"
        command.execute()

        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("com.nonexistent.Driver")
        assertThat(connectionAttempted).isFalse()
    }
}
