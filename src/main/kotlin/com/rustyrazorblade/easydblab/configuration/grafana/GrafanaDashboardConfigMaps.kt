package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.KeyToPathBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.VolumeProjectionBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * The only way core dashboards reach Grafana, on every environment: through the K8s API, with no
 * copy to a node and no SSH.
 *
 * Each rendered dashboard `<folder>/<file>.json` becomes its own ConfigMap,
 * `grafana-dashboard-<folder>-<stem>`, holding one key, the file name, and labelled
 * [Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL] so a deploy can prune the maps of dashboards it no
 * longer renders. One map per file gives each dashboard a stable name: removing one leaves every
 * other map as it was. One projected volume per folder, `dashboards-<folder>`, mounts its files
 * read-only at `<folder>/<file>.json` under [GRAFANA_DASHBOARD_ROOT], where the file provider sweeps
 * them. The maps are applied with the Deployment, so `ConfigHashAnnotator` hashes them and a
 * dashboard change rolls Grafana.
 *
 * Everything that would make a dashboard undeliverable (one over budget, two paths with one map
 * name, a folder that is no valid volume name) is refused here, before anything is applied.
 */
object GrafanaDashboardConfigMaps {
    private const val VOLUME_PREFIX = "dashboards-"
    private const val GRAFANA_CONTAINER = "grafana"
    private const val DNS_1123_LABEL_MAX = 63
    private val DNS_1123_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
    private val NOT_NAME_CHARACTERS = Regex("[^a-z0-9]+")

    /** One dashboard on its way into a ConfigMap. */
    private data class Entry(
        val path: String,
        val json: String,
    ) {
        val folder: String = path.substringBefore('/')
        val fileName: String = path.substringAfter('/')
        val mapName: String =
            Constants.Grafana.DASHBOARD_CONFIGMAP_PREFIX +
                "$folder-${fileName.removeSuffix(".json")}".lowercase().replace(NOT_NAME_CHARACTERS, "-").trim('-')
        val bytes: Int = fileName.toByteArray().size + json.toByteArray().size
    }

    /**
     * [resources] (Grafana's, as `GrafanaManifestBuilder` builds them) preceded by one ConfigMap per
     * dashboard in [tree], with the Grafana container mounting them at [GRAFANA_DASHBOARD_ROOT].
     *
     * @param tree each dashboard's JSON keyed by its path relative to the tree root
     * @throws IllegalArgumentException naming the file or files when a dashboard exceeds the
     *   per-ConfigMap budget, two paths map to one ConfigMap name, or a folder is not a DNS-1123 label
     */
    fun mountInto(
        resources: List<HasMetadata>,
        tree: Map<String, String>,
    ): List<HasMetadata> {
        val entries = tree.map { (path, json) -> Entry(path, json) }
        validate(entries)
        val deployment = resources.filterIsInstance<Deployment>().single()
        val maps = entries.map { configMap(it, deployment.metadata.namespace) }
        val mounted = mount(deployment, entries.groupBy { it.folder })
        return maps + resources.map { if (it === deployment) mounted else it }
    }

    /** The ConfigMap names [tree] renders to, the set a deploy keeps when it prunes. */
    fun namesOf(tree: Map<String, String>): Set<String> = tree.keys.map { Entry(it, "").mapName }.toSet()

    private fun validate(entries: List<Entry>) {
        val budget = Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES
        val oversized = entries.filter { it.bytes > budget }.map { it.path }
        require(oversized.isEmpty()) {
            "These dashboards are each larger than one ConfigMap's $budget-byte budget: ${oversized.joinToString(", ")}."
        }
        val badFolders =
            entries.map { it.folder }.distinct().filter { folder ->
                val volume = VOLUME_PREFIX + folder
                !DNS_1123_LABEL.matches(volume) || volume.length > DNS_1123_LABEL_MAX
            }
        require(badFolders.isEmpty()) {
            "These dashboard folders do not make a valid volume name (lowercase letters, digits and '-', " +
                "at most ${DNS_1123_LABEL_MAX - VOLUME_PREFIX.length} characters): ${badFolders.joinToString(", ")}."
        }
        val clashes = entries.groupBy { it.mapName }.filterValues { it.size > 1 }
        require(clashes.isEmpty()) {
            "These dashboards map to the same ConfigMap name: " +
                clashes.entries.joinToString("; ") { (name, same) -> "$name <- ${same.joinToString(", ") { it.path }}" } + "."
        }
    }

    private fun configMap(
        entry: Entry,
        namespace: String?,
    ): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(entry.mapName)
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/name", GRAFANA_CONTAINER)
            .addToLabels(Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL, Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE)
            .endMetadata()
            .withData<String, String>(mapOf(entry.fileName to entry.json))
            .build()

    /**
     * [deployment] with one projected volume per dashboard folder, mounted at that folder's path.
     * A projected volume shows each top-level entry as a symlink into its `..data` directory, and
     * Grafana's file provider does not descend into a symlinked directory, so one volume holding the
     * folders as subdirectories would provision nothing; per folder, only the files are symlinks.
     */
    private fun mount(
        deployment: Deployment,
        folders: Map<String, List<Entry>>,
    ): Deployment {
        val pod = deployment.spec.template.spec
        val volumes = folders.map { (folder, entries) -> folder to folderVolume(VOLUME_PREFIX + folder, entries) }
        val containers =
            pod.containers.map { container ->
                when (container.name) {
                    GRAFANA_CONTAINER ->
                        ContainerBuilder(container)
                            .addAllToVolumeMounts(
                                volumes.map { (folder, volume) ->
                                    VolumeMountBuilder()
                                        .withName(volume.name)
                                        .withMountPath("$GRAFANA_DASHBOARD_ROOT/$folder")
                                        .withReadOnly(true)
                                        .build()
                                },
                            ).build()
                    else -> container
                }
            }
        return DeploymentBuilder(deployment)
            .editSpec()
            .editTemplate()
            .editSpec()
            .withContainers(containers)
            .addAllToVolumes(volumes.map { it.second })
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /** A projected volume named [name] holding each of [entries] from its own ConfigMap, by file name. */
    private fun folderVolume(
        name: String,
        entries: List<Entry>,
    ): Volume =
        VolumeBuilder()
            .withName(name)
            .withNewProjected()
            .withSources(
                entries.map { entry ->
                    VolumeProjectionBuilder()
                        .withNewConfigMap()
                        .withName(entry.mapName)
                        .withItems(KeyToPathBuilder().withKey(entry.fileName).withPath(entry.fileName).build())
                        .endConfigMap()
                        .build()
                },
            ).endProjected()
            .build()
}
