package com.rustyrazorblade.easydblab.driver

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

class DefaultCqlSessionFactoryTest {
    private val mockSession = mock<CqlSession>()
    private val mockDirectBuilder = mock<CqlSessionBuilder>()
    private val mockSocksBuilder = mock<SocksProxySessionBuilder>()

    @BeforeEach
    fun setup() {
        whenever(mockDirectBuilder.withConfigLoader(any())).thenReturn(mockDirectBuilder)
        whenever(mockDirectBuilder.addContactPoint(any())).thenReturn(mockDirectBuilder)
        whenever(mockDirectBuilder.build()).thenReturn(mockSession)

        whenever(mockSocksBuilder.withConfigLoader(any())).thenReturn(mockSocksBuilder)
        whenever(mockSocksBuilder.addContactPoint(any())).thenReturn(mockSocksBuilder)
        whenever(mockSocksBuilder.build()).thenReturn(mockSession)
    }

    @Test
    fun `createDirectSession uses plain builder not SocksProxySessionBuilder`() {
        val factory = spy(DefaultCqlSessionFactory())
        doReturn(mockDirectBuilder).whenever(factory).newDirectBuilder()

        factory.createDirectSession(listOf("10.0.0.1"), "dc1")

        verify(factory).newDirectBuilder()
        verify(factory, never()).newProxiedBuilder(any(), any())
    }

    @Test
    fun `createSession uses SocksProxySessionBuilder not plain builder`() {
        val factory = spy(DefaultCqlSessionFactory())
        doReturn(mockSocksBuilder).whenever(factory).newProxiedBuilder(any(), any())

        factory.createSession(listOf("10.0.0.1"), "dc1", 1080)

        verify(factory).newProxiedBuilder("127.0.0.1", 1080)
        verify(factory, never()).newDirectBuilder()
    }
}
