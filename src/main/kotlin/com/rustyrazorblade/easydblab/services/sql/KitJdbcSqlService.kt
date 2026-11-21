package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.SocksTcpBridgeFactory
import com.rustyrazorblade.easydblab.proxy.defaultSocksTcpBridgeFactory
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitEndpointHost
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.util.Properties

/**
 * Executes SQL statements against a kit's JDBC endpoint.
 *
 * Resolves the target node from cluster state with [resolveHost], then builds the connection
 * URL via [KitEndpoint.formatUrl]. This replaces the per-kit service classes that previously
 * hard-coded the node type and URL pattern.
 *
 * @property clusterStateManager Provides cluster state for node IP resolution.
 * @property endpoint JDBC endpoint declared in kit.yaml.
 * @property user JDBC username (from the sql capability config); omitted from properties if blank.
 * @property resolveHost The address that serves [endpoint], given the cluster's hosts; defaults to
 *   the first node of the endpoint's node type, which serves a NodePort. The `sql` command passes
 *   [KitEndpointHost.resolve], which finds the node running the pod of a `pod-selector` endpoint.
 * @property connectionFactory Injectable for testing; defaults to [defaultJdbcConnectionFactory].
 * @property bridgeFactory Injectable for testing; defaults to [defaultSocksTcpBridgeFactory].
 */
class KitJdbcSqlService(
    private val clusterStateManager: ClusterStateManager,
    private val endpoint: KitEndpoint,
    private val user: String,
    private val resolveHost: (Map<ServerType, List<ClusterHost>>) -> Result<String> = {
        KitEndpointHost.firstHostOf(endpoint, it)
    },
    private val connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
    private val bridgeFactory: SocksTcpBridgeFactory = defaultSocksTcpBridgeFactory,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Executes [sql] against the endpoint and returns a [SqlQueryResult] on success.
     *
     * Returns [Result.failure] if no node serves the endpoint (see [resolveHost]), before any
     * connection is attempted, or if the JDBC driver throws an exception.
     */
    fun execute(sql: String): Result<SqlQueryResult> =
        runCatching {
            val privateIp = resolveHost(clusterStateManager.load().hosts).getOrThrow()
            val url = endpoint.formatUrl(privateIp)

            val props =
                Properties().apply {
                    if (user.isNotBlank()) setProperty("user", user)
                }

            val socksPort = System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()
            if (socksPort == null) {
                // No tunnel in this network mode, or no proxy: connect directly to the private IP, unchanged.
                log.debug { "Connecting to ${endpoint.name} at $url" }
                connectAndRun(url, props, sql)
            } else {
                // SOCKS-only cluster: forward loopback through the existing tunnel to the private IP.
                bridgeFactory.open(socksPort, privateIp, endpoint.port).use { bridge ->
                    val bridged = endpoint.formatUrl("127.0.0.1", bridge.localPort)
                    log.debug { "Connecting to ${endpoint.name} via SOCKS bridge at $bridged (target $url)" }
                    connectAndRun(bridged, props, sql)
                }
            }
        }

    private fun connectAndRun(
        url: String,
        props: Properties,
        sql: String,
    ): SqlQueryResult =
        connectionFactory.connect(url, props).use { conn ->
            runStatement(conn, sql)
        }

    private fun runStatement(
        conn: Connection,
        sql: String,
    ): SqlQueryResult =
        conn.createStatement().use { stmt ->
            if (stmt.execute(sql)) {
                stmt.resultSet.use { rs ->
                    val meta = rs.metaData
                    val columnCount = meta.columnCount
                    val columns = (1..columnCount).map { meta.getColumnName(it) }
                    val rows = mutableListOf<List<String>>()
                    while (rs.next()) {
                        rows.add((1..columnCount).map { rs.getString(it) ?: "NULL" })
                    }
                    log.debug { "Query complete: ${rows.size} rows, $columnCount columns" }
                    SqlQueryResult(columns = columns, rows = rows)
                }
            } else {
                val count = stmt.updateCount
                log.debug { "Statement complete: $count rows affected" }
                SqlQueryResult(columns = listOf("rows affected"), rows = listOf(listOf(count.toString())))
            }
        }
}
