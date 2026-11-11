package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.TestDashboardCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [GrafanaDashboardCatalog], the classpath discovery that replaced the hand-maintained
 * dashboard enum.
 *
 * The catalog is compared against a filesystem walk of the top-level `dashboards/` tree: the two
 * must agree exactly, or a dashboard someone dropped into the tree never reaches a cluster.
 */
class GrafanaDashboardCatalogTest {
    private companion object {
        val catalog get() = TestDashboardCatalog.catalog
        val home = GrafanaDashboard("infrastructure", "system-overview.json")
    }

    private val dashboardsDir = File("dashboards")

    private fun catalogOf(vararg dashboards: GrafanaDashboard) =
        GrafanaDashboardCatalog(GRAFANA_DASHBOARD_RESOURCE_BASE, dashboards.toList())

    private fun jsonFilesOnDisk(): Set<Pair<String, String>> =
        dashboardsDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { it.parentFile.name to it.name }
            .toSet()

    @Test
    fun `discovers exactly the JSON files under the dashboards tree`() {
        val onDisk = jsonFilesOnDisk()
        assertThat(onDisk).describedAs("dashboards/ must not be empty or the comparison proves nothing").isNotEmpty()

        assertThat(catalog.dashboards.map { it.folder to it.jsonFileName })
            .containsExactlyInAnyOrderElementsOf(onDisk)
    }

    @Test
    fun `dashboards are sorted by folder then file name`() {
        assertThat(catalog.dashboards)
            .isSortedAccordingTo(compareBy({ it.folder }, { it.jsonFileName }))
    }

    @Test
    fun `every discovered resource path resolves on the classpath`() {
        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            val resourcePath = catalog.resourcePathOf(dashboard)
            assertThat(javaClass.getResource(resourcePath))
                .describedAs("$resourcePath is not on the classpath")
                .isNotNull()
        }
    }

    @Test
    fun `every discovered dashboard carries a top-level uid`() {
        // Without a uid Grafana cannot update a dashboard in place, and cross-dashboard links
        // (`/d/<uid>/...`) have nothing stable to point at.
        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            val json = Json.parseToJsonElement(File(dashboardsDir, dashboard.relativePath).readText())
            assertThat(json.jsonObject["uid"])
                .describedAs("${dashboard.relativePath} has no top-level uid")
                .isNotNull()
        }
    }

    @Test
    fun `the shipped tree contains the home dashboard at infrastructure slash system-overview`() {
        assertThat(catalog.dashboards).contains(home)
    }

    @Test
    fun `a catalog without the home dashboard is rejected and the message names its path`() {
        // GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH names this file. Missing, Grafana opens on
        // nothing, so the catalog refuses to exist rather than let that reach a cluster.
        assertThatThrownBy { catalogOf(GrafanaDashboard("cassandra", "cassandra-overview.json")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("infrastructure/system-overview.json")
    }

    @Test
    fun `a system-overview in another folder does not stand in for the home dashboard`() {
        assertThatThrownBy { catalogOf(GrafanaDashboard("cassandra", "system-overview.json")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("infrastructure/system-overview.json")
    }

    @Test
    fun `a second system-overview in another folder is allowed beside the home dashboard`() {
        assertThatCode { catalogOf(GrafanaDashboard("cassandra", "system-overview.json"), home) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a JSON file at the root of the tree is rejected`() {
        // A file dropped at dashboards/<name>.json instead of dashboards/<folder>/<name>.json has
        // no folder to be provisioned into. Skipping it silently is how dashboards go missing.
        assertThatThrownBy {
            GrafanaDashboardCatalog.discover("grafana-catalog-fixtures/stray-root-file")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("stray.json")
    }

    @Test
    fun `discovery under another base reads each dashboard from that base`() {
        // A catalog discovered under another base must resolve its JSON there, or the writer
        // reads every entry from the shipped tree instead.
        val fixtures = GrafanaDashboardCatalog.discover("grafana-catalog-fixtures/alternate-base")

        val resourcePath = fixtures.resourcePathOf(fixtures.dashboards.single())
        assertThat(resourcePath).isEqualTo("/grafana-catalog-fixtures/alternate-base/infrastructure/system-overview.json")
        assertThat(javaClass.getResource(resourcePath)).isNotNull()
    }

    @Test
    fun `two folders may hold a dashboard with the same file name`() {
        // The copied tree keeps each file under its own directory, so nothing about a dashboard
        // has to be unique across folders. This is the case the tree layout exists for.
        val catalog =
            catalogOf(
                GrafanaDashboard("cassandra", "overview.json"),
                home,
                GrafanaDashboard("opensearch", "overview.json"),
            )

        assertThat(catalog.dashboards.map { it.relativePath })
            .containsExactly("cassandra/overview.json", "infrastructure/system-overview.json", "opensearch/overview.json")
    }
}
