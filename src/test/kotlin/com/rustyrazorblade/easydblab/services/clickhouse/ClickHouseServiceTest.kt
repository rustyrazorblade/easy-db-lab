package com.rustyrazorblade.easydblab.services.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

/**
 * Tests for [DefaultClickHouseService]. Uses mock JDBC objects to verify query
 * execution and result mapping without a real cluster.
 */
class ClickHouseServiceTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val dbHost =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.1.10",
            alias = "db0",
            availabilityZone = "us-west-2a",
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
    }

    private fun stateWithDbNode(): ClusterState =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            tailscaleActive = true,
            hosts = mapOf(ServerType.Cassandra to listOf(dbHost)),
        )

    private fun buildService(connection: Connection): DefaultClickHouseService =
        DefaultClickHouseService(
            clusterStateManager = getKoin().get(),
            connectionFactory = { _, _ -> connection },
        )

    private fun mockResultSet(
        columns: List<String>,
        rows: List<List<String>>,
    ): ResultSet {
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
        whenever(rs.getString(any<Int>())).thenAnswer { currentRow[it.getArgument<Int>(0) - 1] }
        return rs
    }

    @Test
    fun `executes query and returns columns and rows`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithDbNode())

        val rs = mockResultSet(listOf("count"), listOf(listOf("99")))
        val stmt = mock<Statement>()
        whenever(stmt.executeQuery(any())).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(conn).execute("SELECT count(*) FROM default.events").getOrThrow()

        assertThat(result.columns).containsExactly("count")
        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0]).containsExactly("99")
    }

    @Test
    fun `no db nodes returns failure before connecting`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf(), hosts = emptyMap()),
        )

        var connectionAttempted = false
        val service =
            DefaultClickHouseService(
                clusterStateManager = getKoin().get(),
                connectionFactory = { _, _ ->
                    connectionAttempted = true
                    mock()
                },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull()?.message).contains("db nodes")
        assertThat(connectionAttempted).isFalse
    }

    @Test
    fun `jdbc exception propagates as Result failure`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithDbNode())

        val service =
            DefaultClickHouseService(
                clusterStateManager = getKoin().get(),
                connectionFactory = { _, _ -> throw java.sql.SQLException("Connection refused") },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull()?.message).contains("Connection refused")
    }
}
