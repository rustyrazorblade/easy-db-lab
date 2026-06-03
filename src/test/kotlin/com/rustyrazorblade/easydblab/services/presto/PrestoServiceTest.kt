package com.rustyrazorblade.easydblab.services.presto

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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

/**
 * Tests for [DefaultPrestoService]. Uses mock JDBC objects to verify query
 * execution and result mapping without a real cluster.
 */
class PrestoServiceTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val appHost =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.1.10",
            alias = "app0",
            availabilityZone = "us-west-2a",
        )

    private val controlHost =
        ClusterHost(
            publicIp = "54.0.0.2",
            privateIp = "10.0.1.1",
            alias = "control0",
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

    private fun stateWithAppNode(): ClusterState =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            tailscaleActive = true,
            hosts =
                mapOf(
                    ServerType.Stress to listOf(appHost),
                    ServerType.Control to listOf(controlHost),
                ),
        )

    private fun buildService(connection: Connection): DefaultPrestoService =
        DefaultPrestoService(
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

        val rowIterator = rows.iterator()
        whenever(rs.next()).thenAnswer { rowIterator.hasNext().also { if (it) rowIterator.next() } }

        var currentRow = listOf<String>()
        val trackingIterator = rows.iterator()
        whenever(rs.next()).thenAnswer {
            if (trackingIterator.hasNext()) {
                currentRow = trackingIterator.next()
                true
            } else {
                false
            }
        }
        whenever(rs.getString(org.mockito.kotlin.any<Int>())).thenAnswer { invocation ->
            currentRow[(invocation.getArgument<Int>(0)) - 1]
        }

        return rs
    }

    @Test
    fun `executes query and returns columns and rows`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val rs = mockResultSet(listOf("count"), listOf(listOf("42")))
        val stmt = mock<Statement>()
        whenever(stmt.executeQuery("SELECT count(*) FROM t")).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(conn).execute("SELECT count(*) FROM t").getOrThrow()

        assertThat(result.columns).containsExactly("count")
        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0]).containsExactly("42")
    }

    @Test
    fun `multiple rows are returned correctly`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val rs = mockResultSet(listOf("id", "name"), listOf(listOf("1", "alice"), listOf("2", "bob")))
        val stmt = mock<Statement>()
        whenever(stmt.executeQuery(org.mockito.kotlin.any())).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(conn).execute("SELECT id, name FROM users").getOrThrow()

        assertThat(result.columns).containsExactly("id", "name")
        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0]).containsExactly("1", "alice")
        assertThat(result.rows[1]).containsExactly("2", "bob")
    }

    @Test
    fun `null values are represented as NULL string`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val meta = mock<ResultSetMetaData>()
        whenever(meta.columnCount).thenReturn(1)
        whenever(meta.getColumnName(1)).thenReturn("val")
        val rs = mock<ResultSet>()
        whenever(rs.metaData).thenReturn(meta)
        var called = false
        whenever(rs.next()).thenAnswer {
            if (!called) {
                called = true
                true
            } else {
                false
            }
        }
        whenever(rs.getString(1)).thenReturn(null)

        val stmt = mock<Statement>()
        whenever(stmt.executeQuery(org.mockito.kotlin.any())).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(conn).execute("SELECT null").getOrThrow()

        assertThat(result.rows[0][0]).isEqualTo("NULL")
    }

    @Test
    fun `no app nodes returns failure before connecting`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            ),
        )

        var connectionAttempted = false
        val service =
            DefaultPrestoService(
                clusterStateManager = getKoin().get(),
                connectionFactory = { _, _ ->
                    connectionAttempted = true
                    mock()
                },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull()?.message).contains("No app nodes")
        assertThat(connectionAttempted).isFalse
    }

    @Test
    fun `jdbc exception propagates as Result failure`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val service =
            DefaultPrestoService(
                clusterStateManager = getKoin().get(),
                connectionFactory = { _, _ -> throw java.sql.SQLException("Connection refused") },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue
        assertThat(result.exceptionOrNull()?.message).contains("Connection refused")
    }
}
