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
 * @property dashboards Every discovered dashboard, sorted by folder then file name
 */
class GrafanaDashboardCatalog(
    val dashboards: List<GrafanaDashboard>,
) {
    /** The dashboard Grafana opens on; see [Constants.Grafana.HOME_DASHBOARD_STEM]. */
    val home: GrafanaDashboard

    init {
        home =
            checkNotNull(dashboards.singleOrNull { it.stem == Constants.Grafana.HOME_DASHBOARD_STEM }) {
                "No dashboard named ${Constants.Grafana.HOME_DASHBOARD_STEM}.json found under " +
                    "$GRAFANA_DASHBOARD_RESOURCE_BASE/<folder>/. It is the Grafana home dashboard and must exist."
            }
    }

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
            return GrafanaDashboardCatalog(dashboards)
        }
    }
}
