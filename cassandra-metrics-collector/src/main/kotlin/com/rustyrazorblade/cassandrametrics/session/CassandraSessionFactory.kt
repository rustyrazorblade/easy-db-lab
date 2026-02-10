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
                .build()

        return CqlSession.builder()
            .addContactPoint(InetSocketAddress(contactPoint, port))
            .withLocalDatacenter(datacenter)
            .withConfigLoader(configLoader)
            .build()
    }
}
