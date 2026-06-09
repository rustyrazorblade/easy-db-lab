package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Properties

/**
 * Executes SQL statements against a kit's JDBC endpoint.
 *
 * Resolves the target node IP from cluster state using the endpoint's declared node type,
 * then builds the connection URL via [KitEndpoint.formatUrl]. This replaces the
 * per-kit service classes that previously hard-coded the node type and URL pattern.
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

            val props =
                Properties().apply {
                    if (user.isNotBlank()) setProperty("user", user)
                }

            connectionFactory.connect(url, props).use { conn ->
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
}
