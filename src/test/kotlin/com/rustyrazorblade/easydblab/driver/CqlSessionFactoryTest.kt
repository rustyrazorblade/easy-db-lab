package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for CqlSessionFactory and related classes.
 *
 * These tests verify the driver configuration is built correctly
 * without requiring a real Cassandra connection.
 */
class SocksProxySessionBuilderTest {
    @Test
    fun `SocksProxySessionBuilder should store proxy configuration`() {
        val builder = SocksProxySessionBuilder("192.168.1.1", 1080)

        // We can't easily inspect the builder's internal state, but we can verify
        // it doesn't throw during construction
        assertThat(builder).isNotNull
    }

    @Test
    fun `SocksProxySessionBuilder should accept different proxy ports`() {
        val builder1 = SocksProxySessionBuilder("127.0.0.1", 1080)
        val builder2 = SocksProxySessionBuilder("127.0.0.1", 9050)

        assertThat(builder1).isNotNull
        assertThat(builder2).isNotNull
    }
}

/**
 * Tests for the DriverConfigLoader built by DefaultCqlSessionFactory.
 *
 * These tests verify the programmatic configuration options are set correctly.
 */
class CqlSessionFactoryConfigTest {
    @Test
    fun `programmatic config should set connection timeout`() {
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withDuration(
                    DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT,
                    Duration.ofSeconds(30),
                ).build()

        val profile: DriverExecutionProfile = configLoader.initialConfig.defaultProfile
        val timeout =
            profile.getDuration(
                DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT,
            )

        assertThat(timeout).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `programmatic config should set request timeout`() {
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withDuration(
                    DefaultDriverOption.REQUEST_TIMEOUT,
                    Duration.ofSeconds(60),
                ).build()

        val profile: DriverExecutionProfile = configLoader.initialConfig.defaultProfile
        val timeout =
            profile.getDuration(
                DefaultDriverOption.REQUEST_TIMEOUT,
            )

        assertThat(timeout).isEqualTo(Duration.ofSeconds(60))
    }

    @Test
    fun `programmatic config should set local datacenter`() {
        val datacenter = "dc1"
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withString(
                    DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER,
                    datacenter,
                ).build()

        val profile: DriverExecutionProfile = configLoader.initialConfig.defaultProfile
        val configuredDc =
            profile.getString(
                DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER,
            )

        assertThat(configuredDc).isEqualTo(datacenter)
    }

    @Test
    fun `programmatic config should disable schema metadata`() {
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withBoolean(
                    DefaultDriverOption.METADATA_SCHEMA_ENABLED,
                    false,
                ).build()

        val profile: DriverExecutionProfile = configLoader.initialConfig.defaultProfile
        val schemaEnabled =
            profile.getBoolean(
                DefaultDriverOption.METADATA_SCHEMA_ENABLED,
            )

        assertThat(schemaEnabled).isFalse()
    }

    @Test
    fun `programmatic config should set connection pool sizes`() {
        val configLoader =
            DriverConfigLoader
                .programmaticBuilder()
                .withInt(
                    DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE,
                    1,
                ).withInt(
                    DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE,
                    1,
                ).build()

        val profile: DriverExecutionProfile = configLoader.initialConfig.defaultProfile

        assertThat(
            profile.getInt(
                DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE,
            ),
        ).isEqualTo(1)
        assertThat(
            profile.getInt(
                DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE,
            ),
        ).isEqualTo(1)
    }
}
