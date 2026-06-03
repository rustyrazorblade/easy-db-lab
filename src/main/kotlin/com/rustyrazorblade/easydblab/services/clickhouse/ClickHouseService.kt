package com.rustyrazorblade.easydblab.services.clickhouse

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.sql.AbstractJdbcSqlService
import com.rustyrazorblade.easydblab.services.sql.JdbcConnectionFactory
import com.rustyrazorblade.easydblab.services.sql.SqlQueryResult
import com.rustyrazorblade.easydblab.services.sql.defaultJdbcConnectionFactory
import java.util.Properties

/** Type alias for consistency with the rest of the ClickHouse command surface. */
typealias ClickHouseQueryResult = SqlQueryResult

/**
 * Executes SQL statements against a running ClickHouse cluster via the ClickHouse JDBC driver.
 */
interface ClickHouseService {
    fun execute(sql: String): Result<ClickHouseQueryResult>
}

/**
 * Default implementation of [ClickHouseService] that connects to the first db node's private IP
 * via the ClickHouse NodePort (30123).
 *
 * The CHI manifest allows connections from `10.0.0.0/8` to cover both the VPC CIDR (10.0.x.x)
 * and the K3s pod CIDR (10.42.0.0/16). Cilium SNATs NodePort traffic to the pod gateway IP
 * (10.42.x.0) rather than the node's VPC IP, so the VPC CIDR alone is insufficient.
 *
 * `compress=0` disables ClickHouse's internal LZ4 block compression in HTTP responses. The JDBC
 * driver (0.7.x) expects a specific LZ4 magic byte (`0x82`) that is incompatible with the
 * compression format emitted by ClickHouse 26.x. HTTP-level transfer encoding is unaffected.
 *
 * Requires Tailscale for direct private IP connectivity from the developer's machine.
 *
 * @property clusterStateManager Provides cluster state including db node IPs.
 * @property connectionFactory Injectable for testing; defaults to [DriverManager.getConnection].
 */
class DefaultClickHouseService(
    clusterStateManager: ClusterStateManager,
    connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
) : AbstractJdbcSqlService(clusterStateManager, ServerType.Cassandra, connectionFactory),
    ClickHouseService {
    companion object {
        /** NodePort that kube-proxy maps to the ClickHouse HTTP port (8123) on each db node. */
        private const val CLICKHOUSE_NODEPORT = 30123
        private const val CLICKHOUSE_USER = "default"
        private const val CLICKHOUSE_DATABASE = "default"
    }

    override val serviceName = "ClickHouse"

    override fun jdbcUrl(privateIp: String) = "jdbc:clickhouse://$privateIp:$CLICKHOUSE_NODEPORT/$CLICKHOUSE_DATABASE?compress=0"

    override fun connectionProperties() =
        Properties().apply {
            setProperty("user", CLICKHOUSE_USER)
        }
}
