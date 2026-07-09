package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import java.util.Properties

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
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    @AfterEach
    fun clearProxyProps() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    private val trinoJdbcEndpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "app",
            port = 8080,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "trino",
        )

    private val postgresJdbcEndpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "db",
            port = 30432,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "postgresql",
        )

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
    fun `no SOCKS proxy property means connection made directly with no socks props`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())
        // PORT_PROPERTY intentionally unset (cleared in setup)

        var capturedProps: Properties? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = trinoJdbcEndpoint,
                user = "trino",
                connectionFactory = { _, props ->
                    capturedProps = Properties().apply { putAll(props) }
                    throw RuntimeException("stop here")
                },
            )

        service.execute("SELECT 1")

        assertThat(capturedProps?.getProperty("socksProxy")).isNull()
        assertThat(capturedProps?.getProperty("socksProxyHost")).isNull()
    }

    @Test
    fun `active proxy port routes trino JDBC via per-connection socksProxy property`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithAppNode())
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        var capturedProps: Properties? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = trinoJdbcEndpoint,
                user = "trino",
                connectionFactory = { _, props ->
                    capturedProps = Properties().apply { putAll(props) }
                    throw RuntimeException("stop here")
                },
            )

        service.execute("SELECT 1")

        assertThat(capturedProps?.getProperty("socksProxy")).isEqualTo("127.0.0.1:1080")
        // per-connection knob must NOT leak into the JVM-global properties
        assertThat(System.getProperty("socksProxyHost")).isNull()
    }

    @Test
    fun `active proxy port routes postgres JDBC via scoped global properties, restored after`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithDbNode())
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        var globalHostDuringConnect: String? = null
        var globalPortDuringConnect: String? = null
        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = postgresJdbcEndpoint,
                user = "pg",
                connectionFactory = { _, _ ->
                    // postgres has no per-connection knob → global props set for the connect window
                    globalHostDuringConnect = System.getProperty("socksProxyHost")
                    globalPortDuringConnect = System.getProperty("socksProxyPort")
                    throw RuntimeException("stop here")
                },
            )

        service.execute("SELECT 1")

        assertThat(globalHostDuringConnect).isEqualTo("127.0.0.1")
        assertThat(globalPortDuringConnect).isEqualTo("1080")
        // restored (cleared) after the connect call returns
        assertThat(System.getProperty("socksProxyHost")).isNull()
        assertThat(System.getProperty("socksProxyPort")).isNull()
    }

    @Test
    fun `scoped global socks restores previous values after connect`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithDbNode())
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
        System.setProperty("socksProxyHost", "preexisting")
        System.setProperty("socksProxyPort", "9999")

        val service =
            KitJdbcSqlService(
                clusterStateManager = getKoin().get(),
                endpoint = postgresJdbcEndpoint,
                user = "pg",
                connectionFactory = { _, _ -> throw RuntimeException("stop here") },
            )

        service.execute("SELECT 1")

        assertThat(System.getProperty("socksProxyHost")).isEqualTo("preexisting")
        assertThat(System.getProperty("socksProxyPort")).isEqualTo("9999")
    }
}
