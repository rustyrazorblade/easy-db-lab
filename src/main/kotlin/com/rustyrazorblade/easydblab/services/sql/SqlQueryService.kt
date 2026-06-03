package com.rustyrazorblade.easydblab.services.sql

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The result of a successfully executed SQL query via JDBC.
 *
 * @property columns Ordered list of column names.
 * @property rows Each row as a list of string values, one per column.
 */
data class SqlQueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
)

/**
 * Factory for creating JDBC connections. Abstracted to allow test injection
 * of mock connections without a real cluster.
 */
fun interface JdbcConnectionFactory {
    fun connect(
        url: String,
        properties: Properties,
    ): Connection
}

/** Default factory that delegates to [DriverManager.getConnection]. */
val defaultJdbcConnectionFactory: JdbcConnectionFactory =
    JdbcConnectionFactory { url, props -> DriverManager.getConnection(url, props) }

/**
 * Shared base for kit SQL services that execute queries via JDBC.
 *
 * Subclasses provide the node type to look up and the JDBC connection URL for a
 * given private IP. The execution loop — look up host, build URL, connect, execute,
 * map ResultSet to rows — is handled here.
 */
abstract class AbstractJdbcSqlService(
    private val clusterStateManager: ClusterStateManager,
    private val serverType: ServerType,
    private val connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
) {
    private val log = KotlinLogging.logger {}

    /** Builds the JDBC connection URL for the given node private IP. */
    protected abstract fun jdbcUrl(privateIp: String): String

    /** JDBC connection properties (user, password, etc.). */
    protected abstract fun connectionProperties(): Properties

    /** Human-readable service name for error messages. */
    protected abstract val serviceName: String

    fun execute(sql: String): Result<SqlQueryResult> =
        runCatching {
            val state = clusterStateManager.load()
            val hosts = state.hosts[serverType]
            if (hosts.isNullOrEmpty()) {
                error("No ${serverType.serverType} nodes found in cluster state. Is the cluster running?")
            }
            val privateIp = hosts.first().privateIp
            val url = jdbcUrl(privateIp)

            log.debug { "Connecting to $serviceName at $url" }

            connectionFactory.connect(url, connectionProperties()).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
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
                }
            }
        }
}
