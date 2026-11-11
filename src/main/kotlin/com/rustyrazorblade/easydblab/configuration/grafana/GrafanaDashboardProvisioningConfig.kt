package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Grafana dashboard provisioning configuration (`provisioning/dashboards/dashboards.yaml`).
 *
 * One file provider sweeps the dashboard tree the CLI copies onto the control node
 * ([GRAFANA_DASHBOARD_ROOT] inside the container) and, with `foldersFromFilesStructure`, files
 * each dashboard into a Grafana folder named after the directory it sits in. No provider names a
 * folder itself: Grafana rejects that combination, and it would put every dashboard in one
 * folder anyway. Nothing here knows which folders or dashboards exist.
 *
 * @property apiVersion Provisioning schema version; Grafana requires 1
 * @property providers The single tree provider
 */
@Serializable
data class GrafanaDashboardProvisioningConfig(
    val apiVersion: Int = 1,
    val providers: List<GrafanaDashboardProvider>,
) {
    /** Serializes this config to YAML for embedding in the provisioning ConfigMap. */
    fun toYaml(): String = YAML.encodeToString(this)

    companion object {
        private val YAML = Yaml(configuration = YamlConfiguration(encodeDefaults = true))

        /** Builds the config with its single provider over the copied dashboard tree. */
        fun forDashboardTree(): GrafanaDashboardProvisioningConfig =
            GrafanaDashboardProvisioningConfig(
                providers =
                    listOf(
                        GrafanaDashboardProvider(
                            name = "dashboards",
                            options = GrafanaDashboardProviderOptions(path = GRAFANA_DASHBOARD_ROOT),
                        ),
                    ),
            )
    }
}

/**
 * One Grafana file-based dashboard provider.
 *
 * Deliberately has no `folder` or `folderUid`: the folder comes from the file structure under
 * [GrafanaDashboardProviderOptions.path].
 *
 * @property name Provider name
 * @property orgId Grafana organisation the dashboards belong to
 * @property type Provider type; only `file` is used
 * @property disableDeletion Whether Grafana keeps a dashboard whose file disappears
 * @property updateIntervalSeconds How often Grafana re-reads the path
 * @property allowUiUpdates Whether the dashboard can be edited in the UI between reloads
 * @property options Where the provider looks for files and how it derives folders
 */
@Serializable
data class GrafanaDashboardProvider(
    val name: String,
    val orgId: Int = 1,
    val type: String = "file",
    val disableDeletion: Boolean = false,
    val updateIntervalSeconds: Int = UPDATE_INTERVAL_SECONDS,
    val allowUiUpdates: Boolean = true,
    val options: GrafanaDashboardProviderOptions,
) {
    private companion object {
        const val UPDATE_INTERVAL_SECONDS = 10
    }
}

/**
 * File provider options.
 *
 * @property path Directory inside the Grafana container the provider sweeps for dashboard JSON
 * @property foldersFromFilesStructure Whether each directory under [path] becomes a Grafana
 *   folder of the same name, looked up by title so kit dashboards installed into a folder of
 *   that name land beside the core ones
 */
@Serializable
data class GrafanaDashboardProviderOptions(
    val path: String,
    val foldersFromFilesStructure: Boolean = true,
)
