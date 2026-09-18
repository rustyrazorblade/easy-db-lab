package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import io.github.classgraph.ClassGraph

/**
 * Every core Grafana dashboard on the classpath, discovered rather than declared.
 *
 * [discover] scans `dashboards/<folder>/<name>.json` on the classpath (the top-level `dashboards/`
 * tree, copied there by Gradle) and yields one [GrafanaDashboard] per file, so adding a dashboard
 * is adding a file and adding a Grafana folder is adding a directory. The constructor validates
 * the result: the home dashboard must be present.
 *
 * @property resourceBase Classpath directory every dashboard's JSON is read from; the shipped
 *   tree unless the catalog was discovered under another base
 * @property dashboards Every discovered dashboard, sorted by folder then file name
 */
class GrafanaDashboardCatalog(
    val resourceBase: String,
    val dashboards: List<GrafanaDashboard>,
) {
    init {
        check(dashboards.any { it.relativePath == Constants.Grafana.HOME_DASHBOARD_PATH }) {
            "Home dashboard ${Constants.Grafana.HOME_DASHBOARD_PATH} is missing from the catalog. " +
                "Grafana opens on it, so it must exist at exactly that path."
        }
    }

    /** Absolute classpath path of [dashboard]'s JSON, as accepted by `Class.getResource`. */
    fun resourcePathOf(dashboard: GrafanaDashboard): String = "/$resourceBase/${dashboard.relativePath}"

    companion object {
        /**
         * Scans the classpath under [resourceBase] and builds the catalog.
         *
         * Only `<folder>/<name>.json` files, exactly one directory deep, are dashboards. A JSON
         * file at the root of the tree or nested deeper has no folder to be provisioned into, so it
         * is an error rather than something to skip: skipped is how dashboards go missing.
         *
         * @param resourceBase Classpath directory holding the folder directories
         * @throws IllegalStateException if a JSON file is not exactly one directory deep or the
         *   home dashboard is absent
         */
        fun discover(resourceBase: String = GRAFANA_DASHBOARD_RESOURCE_BASE): GrafanaDashboardCatalog {
            val dashboards =
                ClassGraph()
                    .acceptPaths(resourceBase)
                    .scan()
                    .use { scan ->
                        scan.allResources
                            .map { it.path.removePrefix("$resourceBase/") }
                            .filter { it.endsWith(".json") }
                            .map { relativePath ->
                                val segments = relativePath.split('/')
                                check(segments.size == 2) {
                                    "$resourceBase/$relativePath is not a dashboard: every dashboard must sit at " +
                                        "$resourceBase/<folder>/<name>.json, exactly one directory deep"
                                }
                                GrafanaDashboard(folder = segments[0], jsonFileName = segments[1])
                            }
                    }.sortedWith(compareBy({ it.folder }, { it.jsonFileName }))
            return GrafanaDashboardCatalog(resourceBase, dashboards)
        }
    }
}
