package com.rustyrazorblade.easydblab.configuration.grafana

/**
 * Root of the provisioning paths inside the Grafana container. Each folder's provider watches
 * `$GRAFANA_DASHBOARD_ROOT-<folder>`; every dashboard in that folder mounts beneath it.
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
 * classpath, and every K8s name and container path is derived from the two discovered strings, so
 * adding a dashboard is dropping a JSON file into a folder directory and nothing else.
 *
 * @property folder Grafana folder the dashboard is filed under: the directory name, verbatim. Kits
 *   install their own dashboards into a folder named exactly after the kit, so a core dashboard
 *   for the same engine must use the same string to land beside them.
 * @property jsonFileName File name, used as the ConfigMap data key and the classpath resource name
 */
data class GrafanaDashboard(
    val folder: String,
    val jsonFileName: String,
) {
    /** File name without its `.json` extension; the base of every derived K8s name. */
    val stem: String get() = jsonFileName.removeSuffix(".json")

    /** K8s ConfigMap holding the dashboard JSON. */
    val configMapName: String get() = "grafana-dashboard-$stem"

    /** Volume name in the Grafana Deployment spec. */
    val volumeName: String get() = "dashboard-$stem"

    /**
     * Provisioning path whose provider owns this dashboard's folder. [mountPath] sits under it,
     * which is what files the dashboard into [folder] rather than nowhere.
     */
    val folderPath: String get() = folderProviderPath(folder)

    /** Where Grafana reads the dashboard JSON inside the container. */
    val mountPath: String get() = "$folderPath/$stem"

    /** Absolute classpath path of the JSON, as accepted by `Class.getResource`. */
    val resourcePath: String get() = "/$GRAFANA_DASHBOARD_RESOURCE_BASE/$folder/$jsonFileName"

    companion object {
        /** Provisioning path watched by the provider for [folder]. */
        fun folderProviderPath(folder: String): String = "$GRAFANA_DASHBOARD_ROOT-$folder"
    }
}
