package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExtensionRegistryTest {
    private val registryYaml =
        """
        extensions:
          duckdb:
            description: "DuckDB via pg_duckdb"
            image: "ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__"
            shared_preload_libraries:
              - pg_duckdb
            create_extensions:
              - pg_duckdb
          postgis:
            description: "Geospatial"
            image: "ghcr.io/cloudnative-pg/postgis:__PG_MAJOR__"
            create_extensions:
              - postgis
        """.trimIndent()

    private val registry = ExtensionRegistry.fromString(registryYaml)

    @Test
    fun `loads all aliases`() {
        assertThat(registry.all()).containsKeys("duckdb", "postgis")
    }

    @Test
    fun `lookup returns alias for known name`() {
        val alias = requireNotNull(registry.lookup("duckdb")) { "Expected 'duckdb' alias to exist" }
        assertThat(alias.image).isEqualTo("ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__")
        assertThat(alias.sharedPreloadLibraries).containsExactly("pg_duckdb")
        assertThat(alias.createExtensions).containsExactly("pg_duckdb")
    }

    @Test
    fun `lookup returns null for unknown alias`() {
        assertThat(registry.lookup("nonexistent")).isNull()
    }

    @Test
    fun `alias without shared_preload_libraries defaults to empty list`() {
        val alias = requireNotNull(registry.lookup("postgis")) { "Expected 'postgis' alias to exist" }
        assertThat(alias.sharedPreloadLibraries).isEmpty()
    }

    @Test
    fun `fromClasspath loads built-in postgres extensions`() {
        val builtin = ExtensionRegistry.fromClasspath("postgres")
        assertThat(builtin.all()).containsKeys("duckdb", "postgis", "timescaledb")
    }

    @Test
    fun `fromClasspath returns empty registry for unknown kit`() {
        val empty = ExtensionRegistry.fromClasspath("nonexistent-kit")
        assertThat(empty.all()).isEmpty()
    }
}
