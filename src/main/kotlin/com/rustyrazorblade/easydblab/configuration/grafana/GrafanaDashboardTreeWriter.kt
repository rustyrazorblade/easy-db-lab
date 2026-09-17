package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Lays the core dashboard tree out on a local filesystem, ready to be copied to the control
 * node.
 *
 * Every catalog entry is written at `<root>/<folder>/<file>.json`, the layout Grafana's file
 * provider turns into one folder per directory. The JSON is copied from the classpath verbatim:
 * dashboards carry Grafana built-ins like `$__rate_interval` that any general substitution would
 * corrupt. The one exception is the profiling dashboard, whose `__PYROSCOPE_URL__` placeholder
 * is replaced with the cluster's Pyroscope URL.
 *
 * @property catalog Every core dashboard on the classpath
 */
class GrafanaDashboardTreeWriter(
    private val catalog: GrafanaDashboardCatalog,
) {
    /**
     * Writes every dashboard under [root].
     *
     * @param root Directory to write the tree into; created if absent
     * @param pyroscopeUrl Value substituted for `__PYROSCOPE_URL__` in the profiling dashboard
     * @throws IllegalStateException if a catalog entry is not on the classpath
     */
    fun writeTo(
        root: Path,
        pyroscopeUrl: String,
    ) {
        catalog.dashboards.forEach { dashboard ->
            val target = root.resolve(dashboard.relativePath)
            target.parent.createDirectories()
            target.writeText(render(dashboard, pyroscopeUrl))
        }
    }

    private fun render(
        dashboard: GrafanaDashboard,
        pyroscopeUrl: String,
    ): String {
        val json =
            GrafanaDashboardTreeWriter::class.java
                .getResourceAsStream(dashboard.resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: error("Dashboard resource not found: ${dashboard.resourcePath}")
        return if (dashboard.stem == Constants.Grafana.PYROSCOPE_DASHBOARD_STEM) {
            json.replace(PYROSCOPE_URL_PLACEHOLDER, pyroscopeUrl)
        } else {
            json
        }
    }

    private companion object {
        const val PYROSCOPE_URL_PLACEHOLDER = "__PYROSCOPE_URL__"
    }
}
