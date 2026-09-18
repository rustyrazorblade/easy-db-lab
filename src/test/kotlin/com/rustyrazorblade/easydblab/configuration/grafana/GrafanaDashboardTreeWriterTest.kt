package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.TestDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter.Companion.PYROSCOPE_URL_PLACEHOLDER
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

    private fun writtenFiles(root: Path): Set<String> =
        root
            .walk()
            .map { it.relativeTo(root).toString() }
            .toSet()

    private fun resourceText(dashboard: GrafanaDashboard): String =
        checkNotNull(javaClass.getResource(catalog.resourcePathOf(dashboard))).readText()

    @Test
    fun `writes exactly the catalog's files at folder slash file and reports their number`(
        @TempDir root: Path,
    ) {
        val count = writer.writeTo(root, pyroscopeUrl)

        assertThat(writtenFiles(root)).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
        assertThat(count).isEqualTo(catalog.dashboards.size)
    }

    @Test
    fun `no written dashboard still carries the Pyroscope placeholder`(
        @TempDir root: Path,
    ) {
        // At least one shipped dashboard must carry it, or this proves nothing about substitution.
        assertThat(catalog.dashboards.filter { resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }).isNotEmpty()

        writer.writeTo(root, pyroscopeUrl)

        assertThat(catalog.dashboards).allSatisfy { dashboard ->
            val json = root.resolve(dashboard.relativePath).readText()
            assertThat(json).describedAs(dashboard.relativePath).doesNotContain(PYROSCOPE_URL_PLACEHOLDER)
        }
        assertThat(catalog.dashboards.map { root.resolve(it.relativePath).readText() }).anyMatch { it.contains(pyroscopeUrl) }
    }

    @Test
    fun `a dashboard without the placeholder is written verbatim`(
        @TempDir root: Path,
    ) {
        // Dashboards don't use __KEY__ variables and must not go through any substitution:
        // TemplateService-style processing corrupts Grafana built-ins like $__rate_interval.
        writer.writeTo(root, pyroscopeUrl)

        val untouched = catalog.dashboards.filter { !resourceText(it).contains(PYROSCOPE_URL_PLACEHOLDER) }
        assertThat(untouched).isNotEmpty()
        assertThat(untouched).allSatisfy { dashboard ->
            assertThat(root.resolve(dashboard.relativePath).readText())
                .describedAs(dashboard.relativePath)
                .isEqualTo(resourceText(dashboard))
        }
    }
}
