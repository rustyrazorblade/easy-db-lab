package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests that the postgres-cluster.yaml.template renders correctly through install-time
 * __KEY__ substitution performed by TemplateService.
 *
 * All variables — INSTANCES, STORAGE_CLASS_WFC, STORAGE_SIZE, IMAGE,
 * SHARED_PRELOAD_LIBRARIES, and POST_INIT_SQL — are substituted at install time.
 */
class PostgresClusterTemplateTest {
    private val templateContent: String by lazy {
        PostgresClusterTemplateTest::class.java.classLoader
            .getResourceAsStream("com/rustyrazorblade/easydblab/kits/postgres/postgres-cluster.yaml.template")
            ?.bufferedReader()
            ?.readText()
            ?: error("postgres-cluster.yaml.template not found on classpath")
    }

    private fun simulateInstall(
        template: String,
        kitName: String = "postgres-duckdb",
        image: String = "ghcr.io/cloudnative-pg/postgresql:17",
        sharedPreloadLibraries: String = "[]",
        postInitSql: String = "[]",
        pgMajorVersion: String = "17",
        postgresUid: String = "26",
        postgresGid: String = "26",
    ): String =
        template
            .replace("__KIT_NAME__", kitName)
            .replace("__INSTANCES__", "1")
            .replace("__STORAGE_CLASS_WFC__", "local-storage-wfc")
            .replace("__STORAGE_SIZE__", "10Gi")
            .replace("__IMAGE__", image)
            .replace("__SHARED_PRELOAD_LIBRARIES__", sharedPreloadLibraries)
            .replace("__POST_INIT_SQL__", postInitSql)
            .replace("__PG_MAJOR_VERSION__", pgMajorVersion)
            .replace("__POSTGRES_UID__", postgresUid)
            .replace("__POSTGRES_GID__", postgresGid)

    @Test
    fun `no extensions renders with default image and empty preload and postInitSQL`() {
        val rendered = simulateInstall(templateContent)

        // Image appears in the ImageCatalog spec.images block (not as imageName)
        assertThat(rendered).contains("image: ghcr.io/cloudnative-pg/postgresql:17")
        assertThat(rendered).contains("shared_preload_libraries: []")
        assertThat(rendered).contains("postInitSQL: []")
        assertThat(rendered).doesNotContain("__IMAGE__")
        assertThat(rendered).doesNotContain("__SHARED_PRELOAD_LIBRARIES__")
        assertThat(rendered).doesNotContain("__POST_INIT_SQL__")
    }

    @Test
    fun `single extension renders with alias image and preload and postInitSQL`() {
        val rendered =
            simulateInstall(
                templateContent,
                image = "timescale/timescaledb-ha:pg17-latest",
                sharedPreloadLibraries = "[\"timescaledb\"]",
                postInitSql = "[\"CREATE EXTENSION timescaledb\"]",
            )

        // Image appears in the ImageCatalog spec.images block (not as imageName)
        assertThat(rendered).contains("image: timescale/timescaledb-ha:pg17-latest")
        assertThat(rendered).contains("shared_preload_libraries: [\"timescaledb\"]")
        assertThat(rendered).contains("postInitSQL: [\"CREATE EXTENSION timescaledb\"]")
    }

    @Test
    fun `all install-time vars are substituted`() {
        val rendered = simulateInstall(templateContent)
        assertThat(rendered).doesNotContain("__KIT_NAME__")
        assertThat(rendered).doesNotContain("__INSTANCES__")
        assertThat(rendered).doesNotContain("__STORAGE_CLASS_WFC__")
        assertThat(rendered).doesNotContain("__STORAGE_SIZE__")
        assertThat(rendered).doesNotContain("__IMAGE__")
        assertThat(rendered).doesNotContain("__SHARED_PRELOAD_LIBRARIES__")
        assertThat(rendered).doesNotContain("__POST_INIT_SQL__")
    }

    @Test
    fun `kit name is used for all K8s resource names`() {
        val rendered = simulateInstall(templateContent, kitName = "postgres-postgis")
        assertThat(rendered).contains("name: postgres-postgis-credentials")
        assertThat(rendered).contains("name: postgres-postgis-catalog")
        assertThat(rendered).contains("name: postgres-postgis\n")
        assertThat(rendered).doesNotContain("name: postgres-credentials")
        assertThat(rendered).doesNotContain("name: postgres-catalog")
    }
}
