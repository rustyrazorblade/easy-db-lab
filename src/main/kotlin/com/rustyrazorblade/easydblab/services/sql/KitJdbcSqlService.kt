package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.SocksTcpBridgeFactory
import com.rustyrazorblade.easydblab.proxy.defaultSocksTcpBridgeFactory
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.util.Properties

/**
 * The Kubernetes Service a kit endpoint is published on, which a port-forward targets. Resolved
 * from the kit's own files by [com.rustyrazorblade.easydblab.services.access.KitEndpointServices].
 *
 * @property namespace the Service's namespace
 * @property name the Service's name
 * @property port the Service port behind the endpoint, or null when no file of the kit declares it
 */
data class KitService(
    val namespace: String,
    val name: String,
    val port: Int? = null,
)

/**
 * Executes SQL statements against a kit's JDBC endpoint.
 *
 * Asks the service-access seam for the kit's [service] on the endpoint's node role and port (a
 * port-forward takes the Service port, or the endpoint's port when the kit does not declare one,
 * which the forward maps from a NodePort on the live Service), then
 * builds the connection URL via [KitEndpoint.formatUrl]. JDBC drivers take no per-connection proxy,
 * so a SOCKS endpoint is dialed through a loopback [com.rustyrazorblade.easydblab.proxy.SocksTcpBridge];
 * any other endpoint is dialed as it is. No JVM-global proxy property is read.
 *
 * @property access the service-access seam
 * @property service the Service the endpoint is published on, or null when the kit declares none (only a port-forward needs it)
 * @property endpoint JDBC endpoint declared in kit.yaml.
 * @property user JDBC username (from the sql capability config); omitted from properties if blank.
 * @property connectionFactory Injectable for testing; defaults to [defaultJdbcConnectionFactory].
 * @property bridgeFactory Injectable for testing; defaults to [defaultSocksTcpBridgeFactory].
 */
class KitJdbcSqlService(
    private val access: ServiceAccess,
    private val service: KitService?,
    private val endpoint: KitEndpoint,
    private val user: String,
    private val connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
    private val bridgeFactory: SocksTcpBridgeFactory = defaultSocksTcpBridgeFactory,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Executes [sql] against the endpoint and returns a [SqlQueryResult] on success.
     *
     * Returns [Result.failure] if the seam cannot open the endpoint (for example, no node of the
     * endpoint's node type is in cluster state), or if the JDBC driver throws an exception.
     */
    fun execute(sql: String): Result<SqlQueryResult> =
        runCatching {
            val target =
                ServiceTarget(
                    service?.namespace ?: Constants.K8s.NAMESPACE,
                    service?.name.orEmpty(),
                    service?.port ?: endpoint.port,
                    ServerType.from(endpoint.nodeType),
                    endpoint.port,
                )
            val props =
                Properties().apply {
                    if (user.isNotBlank()) setProperty("user", user)
                }
            when (val opened = access.open(target)) {
                is ServiceEndpoint.Plain -> {
                    val url = endpoint.formatUrl(opened.host, opened.port)
                    log.debug { "Connecting to ${endpoint.name} at $url" }
                    connectAndRun(url, props, sql)
                }
                is ServiceEndpoint.ViaSocks ->
                    bridgeFactory.open(opened.socksPort, opened.host, opened.port).use { bridge ->
                        val bridged = endpoint.formatUrl(LOOPBACK, bridge.localPort)
                        log.debug { "Connecting to ${endpoint.name} via SOCKS bridge at $bridged (target ${opened.host}:${opened.port})" }
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

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
