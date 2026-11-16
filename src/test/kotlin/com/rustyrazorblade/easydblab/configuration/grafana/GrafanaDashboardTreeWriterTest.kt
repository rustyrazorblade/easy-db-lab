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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * Tests for [GrafanaDashboardTreeWriter], which lays the catalog out on disk exactly as the
 * control node and Grafana's file provider expect it.
 *
 * A file at the wrong depth lands in the wrong Grafana folder (or General) with no error from
 * anyone, so the written layout is compared against the catalog outright.
 */
class GrafanaDashboardTreeWriterTest {
    private val catalog = TestDashboardCatalog.catalog
    private val writer = GrafanaDashboardTreeWriter(catalog)
    private val pyroscopeUrl = "http://10.0.0.1:4040"
    private val context = DashboardInstallContext("lab-abc123", TenantSet.of("acme", listOf("default")), "http://10.0.0.1:3080")

    private fun writtenFiles(root: Path): Set<String> =
        root
            .walk()
            .map { it.relativeTo(root).toString() }
            .toSet()

    private fun resourceText(dashboard: GrafanaDashboard): String =
        checkNotNull(javaClass.getResource(catalog.resourcePathOf(dashboard))).readText()

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `writes exactly the catalog's files at folder slash file and reports their number`(
        @TempDir root: Path,
    ) {
        val count = writer.writeTo(root, pyroscopeUrl, context)

        assertThat(writtenFiles(root)).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
        assertThat(count).isEqualTo(catalog.dashboards.size)
    }

    @Test
    fun `no written dashboard still carries the Pyroscope placeholder`(
        @TempDir root: Path,
    ) {
        // At least one shipped dashboard must carry it, or this proves nothing about substitution.
        assertThat(catalog.dashboards.filter { resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }).isNotEmpty()

        writer.writeTo(root, pyroscopeUrl, context)

        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            val json = root.resolve(dashboard.relativePath).readText()
            assertThat(json).describedAs(dashboard.relativePath).doesNotContain(PYROSCOPE_URL_PLACEHOLDER)
        }
        assertThat(catalog.dashboards.map { root.resolve(it.relativePath).readText() }).anyMatch { it.contains(pyroscopeUrl) }
    }

    @Test
    fun `a dashboard without the placeholder is written as its JSON with only the install-time pass applied`(
        @TempDir root: Path,
    ) {
        // Dashboards must not go through any general substitution: TemplateService-style processing
        // corrupts Grafana built-ins like $__rate_interval.
        writer.writeTo(root, pyroscopeUrl, context)

        val untouched = catalog.dashboards.filter { !resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }
        assertThat(untouched).isNotEmpty()
        assertThat(untouched).allSatisfy { dashboard ->
            assertThat(parse(root.resolve(dashboard.relativePath).readText()))
                .describedAs(dashboard.relativePath)
                .isEqualTo(DashboardDefaults.apply(parse(resourceText(dashboard)), context))
        }
    }

    @Test
    fun `every written dashboard with a cluster variable defaults it to the current cluster`(
        @TempDir root: Path,
    ) {
        writer.writeTo(root, pyroscopeUrl, context)

        val clusters =
            catalog.dashboards.mapNotNull { dashboard ->
                parse(root.resolve(dashboard.relativePath).readText())["templating"]
                    ?.jsonObject
                    ?.get("list")
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.singleOrNull { it["name"]?.jsonPrimitive?.content == "cluster" }
                    ?.let {
                        dashboard.relativePath to
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
