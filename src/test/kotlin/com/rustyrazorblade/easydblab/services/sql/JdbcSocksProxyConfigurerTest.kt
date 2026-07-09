package com.rustyrazorblade.easydblab.services.sql

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Tests for [JdbcSocksProxyConfigurer] — the per-driver SOCKS knob selection that lets
 * `<kit> sql` reach a private node IP through the SOCKS5 tunnel (easy-db-lab#735).
 */
class JdbcSocksProxyConfigurerTest {
    private fun props(vararg pairs: Pair<String, String>): Properties =
        Properties().apply {
            pairs.forEach { (k, v) -> setProperty(k, v) }
        }

    @Test
    fun `trino scheme gets socksProxy host-port property`() {
        val result = JdbcSocksProxyConfigurer.applyToProperties("trino", props("user" to "trino"), 1080)

        assertThat(result.getProperty("socksProxy")).isEqualTo("127.0.0.1:1080")
        // existing properties preserved
        assertThat(result.getProperty("user")).isEqualTo("trino")
    }

    @Test
    fun `presto scheme gets socksProxy host-port property`() {
        val result = JdbcSocksProxyConfigurer.applyToProperties("presto", Properties(), 1234)

        assertThat(result.getProperty("socksProxy")).isEqualTo("127.0.0.1:1234")
    }

    @Test
    fun `mysql scheme gets socksProxyHost and socksProxyPort properties`() {
        val result = JdbcSocksProxyConfigurer.applyToProperties("mysql", Properties(), 1080)

        assertThat(result.getProperty("socksProxyHost")).isEqualTo("127.0.0.1")
        assertThat(result.getProperty("socksProxyPort")).isEqualTo("1080")
        assertThat(result.getProperty("socksProxy")).isNull()
    }

    @Test
    fun `scheme matching is case insensitive`() {
        val result = JdbcSocksProxyConfigurer.applyToProperties("TRINO", Properties(), 1080)

        assertThat(result.getProperty("socksProxy")).isEqualTo("127.0.0.1:1080")
    }

    @Test
    fun `postgresql scheme adds no per-connection property and requires global fallback`() {
        val result = JdbcSocksProxyConfigurer.applyToProperties("postgresql", props("user" to "pg"), 1080)

        assertThat(result.getProperty("socksProxy")).isNull()
        assertThat(result.getProperty("socksProxyHost")).isNull()
        assertThat(result.getProperty("user")).isEqualTo("pg")
        assertThat(JdbcSocksProxyConfigurer.requiresGlobalFallback("postgresql")).isTrue()
    }

    @Test
    fun `clickhouse scheme requires global fallback`() {
        assertThat(JdbcSocksProxyConfigurer.requiresGlobalFallback("clickhouse")).isTrue()
    }

    @Test
    fun `trino and mysql schemes do not require global fallback`() {
        assertThat(JdbcSocksProxyConfigurer.requiresGlobalFallback("trino")).isFalse()
        assertThat(JdbcSocksProxyConfigurer.requiresGlobalFallback("presto")).isFalse()
        assertThat(JdbcSocksProxyConfigurer.requiresGlobalFallback("mysql")).isFalse()
    }

    @Test
    fun `input properties are not mutated`() {
        val input = props("user" to "trino")
        JdbcSocksProxyConfigurer.applyToProperties("trino", input, 1080)

        assertThat(input.getProperty("socksProxy")).isNull()
    }
}
