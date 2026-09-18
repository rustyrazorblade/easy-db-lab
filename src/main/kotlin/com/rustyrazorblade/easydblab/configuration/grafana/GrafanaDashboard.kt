package com.rustyrazorblade.easydblab.configuration.grafana

/**
 * Root of the dashboard tree inside the Grafana container. The single provisioning provider
 * sweeps it, and every core dashboard sits at `$GRAFANA_DASHBOARD_ROOT/<folder>/<file>.json`.
 * It is the hostPath [GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH] seen from inside.
 */
const val GRAFANA_DASHBOARD_ROOT = "/var/lib/grafana/dashboards"

/**
 * Classpath directory holding the dashboard folders. Gradle copies the top-level `dashboards/`
 * tree here (see `processResources` in `build.gradle.kts`), so a file at
 * `dashboards/<folder>/<name>.json` in the repo is `dashboards/<folder>/<name>.json` on the
 * classpath as well.
 */
const val GRAFANA_DASHBOARD_RESOURCE_BASE = "dashboards"

/**
 * One core Grafana dashboard: a JSON file in a folder directory under `dashboards/`.
 *
 * Nothing here is declared by hand. [GrafanaDashboardCatalog] discovers every instance from the
 * classpath, and the tree copied to the control node keeps exactly this `<folder>/<file>` layout,
 * so adding a dashboard is dropping a JSON file into a folder directory and nothing else. Where
 * the JSON is read from is the catalog's knowledge ([GrafanaDashboardCatalog.resourcePathOf]),
 * not the dashboard's.
 *
 * @property folder Grafana folder the dashboard is filed under: the directory name, verbatim. Kits
 *   install their own dashboards into a folder named exactly after the kit, so a core dashboard
 *   for the same engine must use the same string to land beside them.
 * @property jsonFileName File name, used as the classpath resource name and the name in the tree
 */
data class GrafanaDashboard(
    val folder: String,
    val jsonFileName: String,
) {
    /** Path of the JSON relative to the tree root, on the classpath and on the control node alike. */
    val relativePath: String get() = "$folder/$jsonFileName"
}
