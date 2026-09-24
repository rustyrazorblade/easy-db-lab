package com.rustyrazorblade.easydblab.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which dashboards a kit instance installs, and how they are made its own. postgres-duckdb used to
 * install every postgres dashboard (PostGIS and TimescaleDB included) into its folder, and, since
 * Grafana uids are global, installing the overview (uid postgres-overview) moved it out of the
 * postgres folder. An instance now installs the dashboards with no extension plus its own
 * extension's, each under a uid of its own.
 */
class KitDashboardInstanceTest {
    private val refs =
        listOf(
            DashboardRef(path = "dashboards/postgres.json"),
            DashboardRef(path = "dashboards/duckdb.json", extension = "duckdb"),
            DashboardRef(path = "dashboards/postgis.json", extension = "postgis"),
        )

    private val overview =
        """
        {"uid":"postgres-overview","title":"PostgreSQL Overview",
         "links":[{"url":"/d/postgres-duckdb?orgId=1"},{"url":"/d/postgres-postgis"}],
         "panels":[{"targets":[{"expr":"pg_up{job=\"postgres\"}"}]}]}
        """.trimIndent()

    private val duckdb = """{"uid":"postgres-duckdb","title":"DuckDB","links":[{"url":"/d/postgres-overview"}]}"""

    private fun uidOf(json: String) = requireNotNull(Json.parseToJsonElement(json).jsonObject["uid"]).jsonPrimitive.content

    @Test
    fun `an extension instance selects the extension-free dashboards and its own extension's`() {
        assertThat(selectInstanceDashboards(refs, extension = "duckdb").map { it.path })
            .containsExactly("dashboards/postgres.json", "dashboards/duckdb.json")
    }

    @Test
    fun `the plain kit selects only the extension-free dashboards`() {
        assertThat(selectInstanceDashboards(refs, extension = "").map { it.path }).containsExactly("dashboards/postgres.json")
    }

    @Test
    fun `the kit's own instance installs its dashboards unchanged`() {
        val instance = KitDashboardInstance(kitName = "postgres", kitType = "postgres", dashboards = listOf(overview))

        assertThat(instance.rendered().single()).isEqualTo(overview)
    }

    /**
     * Each instance's metrics carry its own job (`job="postgres-duckdb"`), while the kit's
     * dashboards select the kit's (`job="postgres"`). An extension instance's copies select its job.
     */
    @Test
    fun `an extension instance's dashboards select its own scrape job`() {
        val instance = KitDashboardInstance(kitName = "postgres-duckdb", kitType = "postgres", dashboards = listOf(overview))

        val rendered = instance.rendered().single()

        assertThat(rendered).contains("""pg_up{job=\"postgres-duckdb\"}""").doesNotContain("""job=\"postgres\"""")
    }

    @Test
    fun `an extension instance gets its own uids, and its links point at its own copies`() {
        val instance = KitDashboardInstance(kitName = "postgres-duckdb", kitType = "postgres", dashboards = listOf(overview, duckdb))

        val (renderedOverview, renderedDuckdb) = instance.rendered()

        assertThat(uidOf(renderedOverview)).isEqualTo("postgres-overview-duckdb")
        assertThat(uidOf(renderedDuckdb)).isEqualTo("postgres-duckdb-duckdb")
        assertThat(renderedOverview).contains("/d/postgres-duckdb-duckdb?orgId=1")
        // A dashboard this instance does not install keeps pointing at the kit's own copy.
        assertThat(renderedOverview).contains("\"/d/postgres-postgis\"")
        assertThat(renderedDuckdb).contains("/d/postgres-overview-duckdb")
    }
}
