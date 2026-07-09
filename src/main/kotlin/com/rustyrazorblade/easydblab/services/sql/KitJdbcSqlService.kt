package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.util.Properties

/**
 * Executes SQL statements against a kit's JDBC endpoint.
 *
 * Resolves the target node IP from cluster state using the endpoint's declared node type,
 * then builds the connection URL via [KitEndpoint.formatUrl]. This replaces the
 * per-kit service classes that previously hard-coded the node type and URL pattern.
 *
 * When a SOCKS5 tunnel is active (published via [Constants.Proxy.PORT_PROPERTY] on a non-Tailscale
 * cluster), the JDBC connection is routed through it so the private node IP is reachable — see
 * [JdbcSocksProxyConfigurer] and easy-db-lab#735.
 *
 * @property clusterStateManager Provides cluster state for node IP resolution.
 * @property endpoint JDBC endpoint declared in kit.yaml.
 * @property user JDBC username (from the sql capability config); omitted from properties if blank.
 * @property connectionFactory Injectable for testing; defaults to [defaultJdbcConnectionFactory].
 */
class KitJdbcSqlService(
    private val clusterStateManager: ClusterStateManager,
    private val endpoint: KitEndpoint,
    private val user: String,
    private val connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Executes [sql] against the endpoint and returns a [SqlQueryResult] on success.
     *
     * Returns [Result.failure] if no nodes of the endpoint's node type are found in
     * cluster state, or if the JDBC driver throws an exception.
     */
    fun execute(sql: String): Result<SqlQueryResult> =
        runCatching {
            val state = clusterStateManager.load()
            val serverType = ServerType.from(endpoint.nodeType)
            val hosts = state.hosts[serverType]
            if (hosts.isNullOrEmpty()) {
                error("No ${serverType.serverType} nodes found in cluster state. Is the cluster running?")
            }
            val url = endpoint.formatUrl(hosts.first().privateIp)

            log.debug { "Connecting to ${endpoint.name} at $url" }

            val baseProps =
                Properties().apply {
                    if (user.isNotBlank()) setProperty("user", user)
                }

            openConnection(url, baseProps).use { conn ->
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
        }

    /**
     * Opens a JDBC connection, routing it through the active SOCKS5 tunnel when one is published
     * via [Constants.Proxy.PORT_PROPERTY].
     *
     * Drivers with a per-connection SOCKS knob (trino/presto/mysql) get it applied to [baseProps] —
     * leaving all other JVM sockets (notably the AWS SDK) direct. Drivers without one
     * (postgresql/clickhouse) fall back to the JVM-global `socksProxyHost`/`socksProxyPort`
     * properties, scoped tightly around the connect call and restored immediately after; this is
     * safe for the run-and-exit `sql` command, which issues no concurrent AWS traffic.
     *
     * When no proxy port is published (Tailscale, or proxy not started) the connection is made
     * directly with [baseProps] unchanged.
     */
    private fun openConnection(
        url: String,
        baseProps: Properties,
    ): Connection {
        val port = System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()
        if (port == null) {
            return connectionFactory.connect(url, baseProps)
        }

        val scheme = endpoint.scheme
        if (!JdbcSocksProxyConfigurer.requiresGlobalFallback(scheme)) {
            val proxiedProps = JdbcSocksProxyConfigurer.applyToProperties(scheme, baseProps, port)
            log.debug { "Routing $scheme JDBC through SOCKS5 proxy on 127.0.0.1:$port (per-connection)" }
            return connectionFactory.connect(url, proxiedProps)
        }

        log.debug { "Routing $scheme JDBC through SOCKS5 proxy on 127.0.0.1:$port (scoped global properties)" }
        return withScopedGlobalSocks(port) { connectionFactory.connect(url, baseProps) }
    }

    /**
     * Runs [block] with the JVM-global `socksProxyHost`/`socksProxyPort` set to `127.0.0.1:[port]`,
     * restoring the previous values afterward. Used only for drivers lacking a per-connection knob.
     */
    private fun <T> withScopedGlobalSocks(
        port: Int,
        block: () -> T,
    ): T {
        val prevHost = System.getProperty("socksProxyHost")
        val prevPort = System.getProperty("socksProxyPort")
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", "$port")
        try {
            return block()
        } finally {
            restoreOrClear("socksProxyHost", prevHost)
            restoreOrClear("socksProxyPort", prevPort)
        }
    }

    private fun restoreOrClear(
        key: String,
        previous: String?,
    ) {
        if (previous == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, previous)
        }
    }
}
