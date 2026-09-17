package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Grafana dashboard provisioning configuration (`provisioning/dashboards/dashboards.yaml`).
 *
 * Generated from the folders [GrafanaDashboardCatalog] discovered: one file provider per folder,
 * naming the folder outright and watching the path that folder's dashboards mount under. There is
 * no root provider because no dashboard lives outside a folder, and `foldersFromFilesStructure`
 * is deliberately unset: every dashboard already sits in its own subdirectory (one ConfigMap
 * each), which that option would turn into one folder per dashboard.
 *
 * @property apiVersion Provisioning schema version; Grafana requires 1
 * @property providers One provider per Grafana folder
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

        /**
         * Builds one provider per folder.
         *
         * @param folders Grafana folder names, which are the directory names under `dashboards/`
         */
        fun forFolders(folders: List<String>): GrafanaDashboardProvisioningConfig =
            GrafanaDashboardProvisioningConfig(
                providers =
                    folders.map { folder ->
                        GrafanaDashboardProvider(
                            name = folder,
                            folder = folder,
                            folderUid = folder,
                            options = GrafanaDashboardProviderOptions(path = GrafanaDashboard.folderProviderPath(folder)),
                        )
                    },
            )
    }
}

/**
 * One Grafana file-based dashboard provider.
 *
 * @property name Provider name; the folder's directory name
 * @property orgId Grafana organisation the folder belongs to
 * @property folder Folder title shown in Grafana; the directory name, verbatim, so kit dashboards
 *   installed into a folder of the same name land beside the core ones
 * @property folderUid Stable folder identifier; also the directory name
 * @property type Provider type; only `file` is used
 * @property disableDeletion Whether Grafana keeps a dashboard whose file disappears
 * @property updateIntervalSeconds How often Grafana re-reads the path
 * @property allowUiUpdates Whether the dashboard can be edited in the UI between reloads
 * @property options Where the provider looks for files
 */
@Serializable
data class GrafanaDashboardProvider(
    val name: String,
    val orgId: Int = 1,
    val folder: String,
    val folderUid: String,
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
 */
@Serializable
data class GrafanaDashboardProviderOptions(
    val path: String,
)
