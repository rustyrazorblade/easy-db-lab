package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExtensionResolverTest {
    private val registry =
        ExtensionRegistry.fromString(
            """
            extensions:
              duckdb:
                description: "DuckDB"
                image: "ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__"
                shared_preload_libraries:
                  - pg_duckdb
                create_extensions:
                  - pg_duckdb
              postgis:
                description: "PostGIS"
                image: "ghcr.io/cloudnative-pg/postgis:__PG_MAJOR__"
                create_extensions:
                  - postgis
              timescaledb:
                description: "TimescaleDB"
                image: "timescale/timescaledb-ha:pg__PG_MAJOR__-latest"
                shared_preload_libraries:
                  - timescaledb
                create_extensions:
                  - timescaledb
            """.trimIndent(),
        )

    private val resolver = ExtensionResolver(registry, "17")

    @Test
    fun `no extensions resolves to default image`() {
        val config = resolver.resolve(emptyList(), null, emptyList(), emptyList())
        assertThat(config.image).isEqualTo("ghcr.io/cloudnative-pg/postgresql:17")
        assertThat(config.sharedPreloadLibraries).isEmpty()
        assertThat(config.createExtensions).isEmpty()
    }

    @Test
    fun `single alias resolves image and pg major substitution`() {
        val config = resolver.resolve(listOf("duckdb"), null, emptyList(), emptyList())
        assertThat(config.image).isEqualTo("ghcr.io/duckdb/pg_duckdb:pg17")
        assertThat(config.sharedPreloadLibraries).containsExactly("pg_duckdb")
        assertThat(config.createExtensions).containsExactly("pg_duckdb")
    }

    @Test
    fun `--image override replaces alias image but alias config still applies`() {
        val config = resolver.resolve(listOf("duckdb"), "my-registry/custom:pg17", emptyList(), emptyList())
        assertThat(config.image).isEqualTo("my-registry/custom:pg17")
        assertThat(config.sharedPreloadLibraries).containsExactly("pg_duckdb")
        assertThat(config.createExtensions).containsExactly("pg_duckdb")
    }

    @Test
    fun `multiple aliases with same image succeeds`() {
        val duckdbWithPostgis =
            ExtensionRegistry.fromString(
                """
                extensions:
                  ext1:
                    description: "first"
                    image: "combined:pg__PG_MAJOR__"
                    create_extensions:
                      - ext1
                  ext2:
                    description: "second"
                    image: "combined:pg__PG_MAJOR__"
                    create_extensions:
                      - ext2
                """.trimIndent(),
            )
        val sameImageResolver = ExtensionResolver(duckdbWithPostgis, "16")
        val config = sameImageResolver.resolve(listOf("ext1", "ext2"), null, emptyList(), emptyList())
        assertThat(config.image).isEqualTo("combined:pg16")
        assertThat(config.createExtensions).containsExactlyInAnyOrder("ext1", "ext2")
    }

    @Test
    fun `multiple aliases with different images throws with actionable message`() {
        assertThatThrownBy {
            resolver.resolve(listOf("duckdb", "postgis"), null, emptyList(), emptyList())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("duckdb")
            .hasMessageContaining("postgis")
            .hasMessageContaining("--image")
    }

    @Test
    fun `--image override resolves conflict between multiple aliases`() {
        val config = resolver.resolve(listOf("duckdb", "postgis"), "combined:pg17", emptyList(), emptyList())
        assertThat(config.image).isEqualTo("combined:pg17")
        assertThat(config.createExtensions).containsExactlyInAnyOrder("pg_duckdb", "postgis")
    }

    @Test
    fun `escape-hatch flags augment alias config`() {
        val config =
            resolver.resolve(
                extensions = listOf("duckdb"),
                imageOverride = null,
                additionalPreload = listOf("extra_lib"),
                additionalCreate = listOf("extra_ext"),
            )
        assertThat(config.sharedPreloadLibraries).containsExactlyInAnyOrder("pg_duckdb", "extra_lib")
        assertThat(config.createExtensions).containsExactlyInAnyOrder("pg_duckdb", "extra_ext")
    }

    @Test
    fun `pure escape hatch with no alias`() {
        val config =
            resolver.resolve(
                extensions = emptyList(),
                imageOverride = "my-custom:pg17",
                additionalPreload = listOf("mylib"),
                additionalCreate = listOf("myext"),
            )
        assertThat(config.image).isEqualTo("my-custom:pg17")
        assertThat(config.sharedPreloadLibraries).containsExactly("mylib")
        assertThat(config.createExtensions).containsExactly("myext")
    }

    @Test
    fun `unknown alias throws with helpful message`() {
        assertThatThrownBy {
            resolver.resolve(listOf("notreal"), null, emptyList(), emptyList())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("notreal")
    }

    @Test
    fun `pg major is major version only when version has dot`() {
        val resolver16 = ExtensionResolver(registry, "16.3")
        val config = resolver16.resolve(listOf("duckdb"), null, emptyList(), emptyList())
        assertThat(config.image).isEqualTo("ghcr.io/duckdb/pg_duckdb:pg16")
    }

    @Test
    fun `alias with custom UID and GID resolves those values`() {
        val registryWithUid =
            ExtensionRegistry.fromString(
                """
                extensions:
                  custom-uid:
                    description: "Uses Docker postgres base image with UID 999"
                    image: "example/pgext:pg__PG_MAJOR__"
                    create_extensions:
                      - pgext
                    postgres_uid: 999
                    postgres_gid: 999
                """.trimIndent(),
            )
        val config = ExtensionResolver(registryWithUid, "17").resolve(listOf("custom-uid"), null, emptyList(), emptyList())
        assertThat(config.postgresUid).isEqualTo(999)
        assertThat(config.postgresGid).isEqualTo(999)
    }

    @Test
    fun `alias without UID override defaults to CNPG base image UID 26`() {
        val config = resolver.resolve(listOf("postgis"), null, emptyList(), emptyList())
        assertThat(config.postgresUid).isEqualTo(26)
        assertThat(config.postgresGid).isEqualTo(26)
    }
}
