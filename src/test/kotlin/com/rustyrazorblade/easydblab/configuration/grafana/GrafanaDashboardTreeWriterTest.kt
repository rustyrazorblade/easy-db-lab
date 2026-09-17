package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
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
    private val catalog = GrafanaDashboardCatalog.discover()
    private val writer = GrafanaDashboardTreeWriter(catalog)
    private val pyroscopeUrl = "http://10.0.0.1:4040"

    private fun writtenFiles(root: Path): Set<String> =
        root
            .walk()
            .map { it.relativeTo(root).toString() }
            .toSet()

    @Test
    fun `writes exactly the catalog's files at folder slash file`(
        @TempDir root: Path,
    ) {
        writer.writeTo(root, pyroscopeUrl)

        assertThat(writtenFiles(root)).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
    }

    @Test
    fun `the profiling dashboard gets the Pyroscope URL substituted`(
        @TempDir root: Path,
    ) {
        writer.writeTo(root, pyroscopeUrl)

        val profiling = catalog.dashboards.single { it.stem == Constants.Grafana.PYROSCOPE_DASHBOARD_STEM }
        val json = root.resolve(profiling.relativePath).readText()
        assertThat(json).contains(pyroscopeUrl)
        assertThat(json).doesNotContain("__PYROSCOPE_URL__")
    }

    @Test
    fun `every other dashboard is written verbatim`(
        @TempDir root: Path,
    ) {
        // Dashboards don't use __KEY__ variables and must not go through any substitution:
        // TemplateService-style processing corrupts Grafana built-ins like $__rate_interval.
        writer.writeTo(root, pyroscopeUrl)

        val others = catalog.dashboards.filter { it.stem != Constants.Grafana.PYROSCOPE_DASHBOARD_STEM }
        assertThat(others).isNotEmpty()
        assertThat(others).allSatisfy { dashboard ->
            assertThat(root.resolve(dashboard.relativePath).readText())
                .describedAs(dashboard.relativePath)
                .isEqualTo(javaClass.getResource(dashboard.resourcePath)!!.readText())
        }
    }
}
