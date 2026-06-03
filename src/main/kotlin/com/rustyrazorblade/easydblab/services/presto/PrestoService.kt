package com.rustyrazorblade.easydblab.services.presto

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.sql.AbstractJdbcSqlService
import com.rustyrazorblade.easydblab.services.sql.JdbcConnectionFactory
import com.rustyrazorblade.easydblab.services.sql.SqlQueryResult
import com.rustyrazorblade.easydblab.services.sql.defaultJdbcConnectionFactory
import java.util.Properties

/** Type alias so existing references to [PrestoQueryResult] continue to compile. */
typealias PrestoQueryResult = SqlQueryResult

/**
 * Executes SQL statements against a running Presto cluster via the Presto JDBC driver.
 */
interface PrestoService {
    fun execute(sql: String): Result<PrestoQueryResult>
}

/**
 * Default implementation of [PrestoService] that connects directly to the first app node's
 * private IP via the Presto JDBC driver.
 *
 * Requires Tailscale for direct private IP connectivity.
 *
 * The Presto JDBC driver (com.facebook.presto:presto-jdbc) does not reliably auto-register
 * via ServiceLoader in all JVM environments. The [init] block forces class loading so that
 * [java.sql.DriverManager] can find the driver when [execute] is called.
 *
 * @property clusterStateManager Provides cluster state including app node IPs.
 * @property connectionFactory Injectable for testing; defaults to [DriverManager.getConnection].
 */
class DefaultPrestoService(
    clusterStateManager: ClusterStateManager,
    connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
) : AbstractJdbcSqlService(clusterStateManager, ServerType.Stress, connectionFactory),
    PrestoService {
    init {
        Class.forName("com.facebook.presto.jdbc.PrestoDriver")
    }

    companion object {
        private const val PRESTO_PORT = 8080
        private const val PRESTO_USER = "easy-db-lab"
    }

    override val serviceName = "Presto"

    override fun jdbcUrl(privateIp: String) = "jdbc:presto://$privateIp:$PRESTO_PORT"

    override fun connectionProperties() =
        Properties().apply {
            setProperty("user", PRESTO_USER)
        }
}
