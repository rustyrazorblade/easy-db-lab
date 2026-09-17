package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
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
    private val catalog = GrafanaDashboardCatalog.discover()

    private val dashboardsDir = File("dashboards")

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
            assertThat(javaClass.getResource(dashboard.resourcePath))
                .describedAs("${dashboard.resourcePath} is not on the classpath")
                .isNotNull()
        }
    }

    @Test
    fun `every discovered dashboard carries a top-level uid`() {
        // Without a uid Grafana cannot update a dashboard in place, and cross-dashboard links
        // (`/d/<uid>/...`) have nothing stable to point at.
        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            val json = Json.parseToJsonElement(File(dashboardsDir, "${dashboard.folder}/${dashboard.jsonFileName}").readText())
            assertThat(json.jsonObject["uid"])
                .describedAs("${dashboard.resourcePath} has no top-level uid")
                .isNotNull()
        }
    }

    @Test
    fun `the home dashboard is system-overview`() {
        assertThat(catalog.home.stem).isEqualTo(Constants.Grafana.HOME_DASHBOARD_STEM)
        assertThat(catalog.home.folder).isEqualTo("infrastructure")
    }

    @Test
    fun `a catalog without the home dashboard is rejected`() {
        // GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH names this file. Missing, Grafana opens on
        // nothing, so the catalog refuses to exist rather than let that reach a cluster.
        assertThatThrownBy {
            GrafanaDashboardCatalog(listOf(GrafanaDashboard("cassandra", "cassandra-overview.json")))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(Constants.Grafana.HOME_DASHBOARD_STEM)
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
    fun `two folders may hold a dashboard with the same file name`() {
        // The copied tree keeps each file under its own directory, so nothing about a dashboard
        // has to be unique across folders. This is the case the tree layout exists for.
        val catalog =
            GrafanaDashboardCatalog(
                listOf(
                    GrafanaDashboard("cassandra", "overview.json"),
                    GrafanaDashboard("infrastructure", "system-overview.json"),
                    GrafanaDashboard("opensearch", "overview.json"),
                ),
            )

        assertThat(catalog.dashboards.map { it.relativePath })
            .containsExactly("cassandra/overview.json", "infrastructure/system-overview.json", "opensearch/overview.json")
    }
}
