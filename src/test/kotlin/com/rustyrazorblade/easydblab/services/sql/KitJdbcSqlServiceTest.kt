package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.SocksTcpBridge
import com.rustyrazorblade.easydblab.proxy.SocksTcpBridgeFactory
import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

/**
 * Tests for [KitJdbcSqlService]. Verifies node resolution from cluster state and
 * correct JDBC result mapping without a real cluster or database.
 */
class KitJdbcSqlServiceTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val appHost =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.1.10",
            alias = "app0",
            availabilityZone = "us-west-2a",
        )

    private val dbHost =
        ClusterHost(
            publicIp = "54.0.0.3",
            privateIp = "10.0.1.20",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private val controlHost =
        ClusterHost(
            publicIp = "54.0.0.2",
            privateIp = "10.0.1.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    private val prestoJdbcEndpoint =
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
    }

    private fun stateWithAppNode(): ClusterState =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            hosts =
                mapOf(
                    ServerType.Stress to listOf(appHost),
                    ServerType.Control to listOf(controlHost),
                ),
        )

    private fun stateWithDbNode(): ClusterState =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(dbHost),
                    ServerType.Control to listOf(controlHost),
                ),
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

    private fun buildService(
        endpoint: KitEndpoint = prestoJdbcEndpoint,
        user: String = "easy-db-lab",
        connection: Connection,
    ): KitJdbcSqlService =
        KitJdbcSqlService(
            clusterStateManager = getKoin().get(),
            endpoint = endpoint,
            user = user,
            connectionFactory = { _, _ -> connection },
        )

    @Test
    fun `executes query and returns columns and rows`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val rs = mockResultSet(listOf("count"), listOf(listOf("42")))
        val stmt = mock<Statement>()
        whenever(stmt.execute("SELECT count(*) FROM t")).thenReturn(true)
        whenever(stmt.resultSet).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(connection = conn).execute("SELECT count(*) FROM t").getOrThrow()

        assertThat(result.columns).containsExactly("count")
        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0]).containsExactly("42")
    }

    @Test
    fun `multiple rows are returned correctly`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val rs = mockResultSet(listOf("id", "name"), listOf(listOf("1", "alice"), listOf("2", "bob")))
        val stmt = mock<Statement>()
        whenever(stmt.execute(org.mockito.kotlin.any())).thenReturn(true)
        whenever(stmt.resultSet).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(connection = conn).execute("SELECT id, name FROM users").getOrThrow()

        assertThat(result.columns).containsExactly("id", "name")
        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0]).containsExactly("1", "alice")
        assertThat(result.rows[1]).containsExactly("2", "bob")
    }

    @Test
    fun `null column values are represented as NULL string`() {
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
        whenever(stmt.execute(org.mockito.kotlin.any())).thenReturn(true)
        whenever(stmt.resultSet).thenReturn(rs)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(connection = conn).execute("SELECT null").getOrThrow()

        assertThat(result.rows[0][0]).isEqualTo("NULL")
    }

    @Test
    fun `DDL statement returns rows affected when no result set`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val stmt = mock<Statement>()
        whenever(stmt.execute(org.mockito.kotlin.any())).thenReturn(false)
        whenever(stmt.updateCount).thenReturn(3)
        val conn = mock<Connection>()
        whenever(conn.createStatement()).thenReturn(stmt)

        val result = buildService(connection = conn).execute("INSERT INTO t VALUES (1),(2),(3)").getOrThrow()

        assertThat(result.columns).containsExactly("rows affected")
        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0]).containsExactly("3")
    }

    @Test
    fun `no nodes of endpoint node type returns failure before connecting`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(controlHost)),
            ),
        )

        var connectionAttempted = false
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = prestoJdbcEndpoint,
                user = "easy-db-lab",
                connectionFactory = { _, _ ->
                    connectionAttempted = true
                    mock()
                },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("No app nodes")
        assertThat(connectionAttempted).isFalse()
    }

    @Test
    fun `jdbc exception propagates as Result failure`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = prestoJdbcEndpoint,
                user = "easy-db-lab",
                connectionFactory = { _, _ -> throw java.sql.SQLException("Connection refused") },
            )

        val result = service.execute("SELECT 1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Connection refused")
    }

    @Test
    fun `endpoint formatUrl is used to build the connection URL`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        var capturedUrl: String? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = prestoJdbcEndpoint,
                user = "easy-db-lab",
                connectionFactory = { url, _ ->
                    capturedUrl = url
                    throw RuntimeException("stop here")
                },
            )

        service.execute("SELECT 1")

        assertThat(capturedUrl).isEqualTo("jdbc:presto://10.0.1.10:8080/cassandra")
    }

    @Test
    fun `resolves db node type from endpoint node-type field`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithDbNode())

        val clickhouseEndpoint =
            KitEndpoint(
                name = "JDBC",
                nodeType = "db",
                port = 30123,
                type = KitEndpoint.EndpointType.JDBC,
                scheme = "clickhouse",
                path = "/default?compress=0",
            )

        var capturedUrl: String? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = clickhouseEndpoint,
                user = "default",
                connectionFactory = { url, _ ->
                    capturedUrl = url
                    throw RuntimeException("stop here")
                },
            )

        service.execute("SELECT 1")

        assertThat(capturedUrl).isEqualTo("jdbc:clickhouse://10.0.1.20:30123/default?compress=0")
    }

    @Test
    fun `routes through the SOCKS bridge and rewrites the url authority when a proxy port is published`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        val fakeBridge = mock<SocksTcpBridge>()
        whenever(fakeBridge.localPort).thenReturn(FAKE_LOCAL_PORT)

        var openedSocksPort = -1
        var openedHost = ""
        var openedPort = -1
        val bridgeFactory =
            SocksTcpBridgeFactory { socksPort, host, port ->
                openedSocksPort = socksPort
                openedHost = host
                openedPort = port
                fakeBridge
            }

        var capturedUrl: String? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = prestoJdbcEndpoint,
                user = "easy-db-lab",
                connectionFactory = { url, _ ->
                    capturedUrl = url
                    throw RuntimeException("stop here")
                },
                bridgeFactory = bridgeFactory,
            )

        try {
            System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
            service.execute("SELECT 1")
        } finally {
            System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        }

        // Bridge opened toward the private endpoint via the published SOCKS port.
        assertThat(openedSocksPort).isEqualTo(1080)
        assertThat(openedHost).isEqualTo("10.0.1.10")
        assertThat(openedPort).isEqualTo(8080)
        // Only the authority is rewritten to the loopback listener; the rest of the URL is intact.
        assertThat(capturedUrl).isEqualTo("jdbc:presto://127.0.0.1:$FAKE_LOCAL_PORT/cassandra")
        // The bridge is always torn down, even though the connection attempt failed.
        verify(fakeBridge).close()
    }

    @Test
    fun `connects directly to the private ip and never opens a bridge when no proxy port is published`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())

        var bridgeOpened = false
        val bridgeFactory =
            SocksTcpBridgeFactory { _, _, _ ->
                bridgeOpened = true
                mock()
            }

        var capturedUrl: String? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = prestoJdbcEndpoint,
                user = "easy-db-lab",
                connectionFactory = { url, _ ->
                    capturedUrl = url
                    throw RuntimeException("stop here")
                },
                bridgeFactory = bridgeFactory,
            )

        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        service.execute("SELECT 1")

        assertThat(bridgeOpened).isFalse()
        assertThat(capturedUrl).isEqualTo("jdbc:presto://10.0.1.10:8080/cassandra")
    }

    private companion object {
        const val FAKE_LOCAL_PORT = 54321
    }
}
