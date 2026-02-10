package com.rustyrazorblade.cassandrametrics.session

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import java.net.InetSocketAddress
import java.time.Duration

object CassandraSessionFactory {
    fun create(
        contactPoint: String,
        port: Int,
        datacenter: String,
    ): CqlSession {
        val configLoader =
            DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(10))
                .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
                .withBoolean(DefaultDriverOption.RECONNECT_ON_INIT, true)
                // Disable token map - we don't need token-aware routing for virtual table queries
                .withBoolean(DefaultDriverOption.METADATA_TOKEN_MAP_ENABLED, false)
                // Only maintain 1 connection to local node, 0 to remote nodes
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 1)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 0)
                .build()

        return CqlSession.builder()
            .addContactPoint(InetSocketAddress(contactPoint, port))
            .withLocalDatacenter(datacenter)
            .withConfigLoader(configLoader)
            // Node filter ensures the driver only connects to the local contact point,
            // even though it may discover other nodes via gossip/system tables
            .withNodeFilter { node ->
                val socketAddress = node.endPoint.resolve()
                if (socketAddress is InetSocketAddress) {
                    socketAddress.address.hostAddress == contactPoint && socketAddress.port == port
                } else {
                    false
                }
            }
            .build()
    }
}
