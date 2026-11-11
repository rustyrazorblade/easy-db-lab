package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Lays the core dashboard tree out on a local filesystem, ready to be copied to the control
 * node.
 *
 * Every catalog entry is written at `<root>/<folder>/<file>.json`, the layout Grafana's file
 * provider turns into one folder per directory. Each JSON is read from the classpath, `__PYROSCOPE_URL__`
 * becomes the cluster's Pyroscope URL in whichever dashboards carry it, and the result goes through
 * the install-time pass, [DashboardDefaults], for the cluster being installed. Nothing else is
 * touched, and no `TemplateService` is involved: dashboards carry Grafana built-ins like
 * `$__rate_interval` that any general substitution would corrupt.
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
     * @param pyroscopeUrl Value substituted for `__PYROSCOPE_URL__`
     * @param context The cluster the dashboards are installed on, for [DashboardDefaults]
     * @return Number of files written
     * @throws IllegalStateException if a catalog entry is not on the classpath
     */
    fun writeTo(
        root: Path,
        pyroscopeUrl: String,
        context: DashboardInstallContext,
    ): Int {
        catalog.dashboards.forEach { dashboard ->
            val target = root.resolve(dashboard.relativePath)
            target.parent.createDirectories()
            target.writeText(render(dashboard, pyroscopeUrl, context))
        }
        return catalog.dashboards.size
    }

    private fun render(
        dashboard: GrafanaDashboard,
        pyroscopeUrl: String,
        context: DashboardInstallContext,
    ): String {
        val resourcePath = catalog.resourcePathOf(dashboard)
        val json =
            GrafanaDashboardTreeWriter::class.java
                .getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: error("Dashboard resource not found: $resourcePath")
        val dashboardJson = Json.parseToJsonElement(json.replace(PYROSCOPE_URL_PLACEHOLDER, pyroscopeUrl)).jsonObject
        // JsonElement.toString keeps every number as written; the serializer would reformat 0.0001 as 1.0E-4.
        return DashboardDefaults.apply(dashboardJson, context).toString()
    }

    companion object {
        /** Placeholder a dashboard carries where the cluster's Pyroscope URL belongs. */
        const val PYROSCOPE_URL_PLACEHOLDER = "__PYROSCOPE_URL__"
    }
}
