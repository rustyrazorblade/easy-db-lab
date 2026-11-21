package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.TestDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter.Companion.PYROSCOPE_URL_PLACEHOLDER
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [GrafanaDashboardTreeWriter.render], which renders the catalog keyed exactly as
 * Grafana's file provider sees it once the ConfigMaps are mounted.
 *
 * A dashboard at the wrong path lands in the wrong Grafana folder (or General) with no error from
 * anyone, so the rendered paths are compared against the catalog outright.
 */
class GrafanaDashboardTreeWriterTest {
    private val catalog = TestDashboardCatalog.catalog
    private val writer = GrafanaDashboardTreeWriter(catalog)
    private val pyroscopeUrl = "http://10.0.0.1:4040"
    private val context = DashboardInstallContext("lab-abc123", TenantSet.of("acme", listOf("default")))
    private val rendered by lazy { writer.render(pyroscopeUrl, context) }

    private fun resourceText(dashboard: GrafanaDashboard): String =
        checkNotNull(javaClass.getResource(catalog.resourcePathOf(dashboard))).readText()

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `renders exactly the catalog's dashboards keyed by folder slash file`() {
        assertThat(rendered.keys).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
    }

    @Test
    fun `no rendered dashboard still carries the Pyroscope placeholder`() {
        // At least one shipped dashboard must carry it, or this proves nothing about substitution.
        assertThat(catalog.dashboards.filter { resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }).isNotEmpty()

        assertThat(rendered).allSatisfy { path, json ->
            assertThat(json).describedAs(path).doesNotContain(PYROSCOPE_URL_PLACEHOLDER)
        }
        assertThat(rendered.values).anyMatch { it.contains(pyroscopeUrl) }
    }

    @Test
    fun `a dashboard without the placeholder is rendered as its JSON with only the install-time pass applied`() {
        // Dashboards must not go through any general substitution: TemplateService-style processing
        // corrupts Grafana built-ins like $__rate_interval.
        val untouched = catalog.dashboards.filter { !resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }
        assertThat(untouched).isNotEmpty()
        assertThat(untouched).allSatisfy { dashboard ->
            assertThat(parse(rendered.getValue(dashboard.relativePath)))
                .describedAs(dashboard.relativePath)
                .isEqualTo(DashboardDefaults.apply(parse(resourceText(dashboard)), context))
        }
    }

    @Test
    fun `every rendered dashboard with a cluster variable defaults it to the current cluster`() {
        val clusters =
            rendered.mapNotNull { (path, json) ->
                parse(json)["templating"]
                    ?.jsonObject
                    ?.get("list")
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.singleOrNull { it["name"]?.jsonPrimitive?.content == "cluster" }
                    ?.let {
                        path to
                            it
                                .getValue("current")
                                .jsonObject
                                .getValue("value")
                                .toString()
                    }
            }
        assertThat(clusters).isNotEmpty()
        assertThat(clusters).allSatisfy { (path, value) -> assertThat(value).describedAs(path).contains("\"lab-abc123\"") }
    }
}
