package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.KeyToPathBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.VolumeProjectionBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * The dashboard tree delivered through the K8s API, for an environment whose Grafana keeps its state
 * on a claim: there is no node directory to copy the tree into, and no node to SSH to.
 *
 * The rendered tree is packed, in catalog order, into `grafana-dashboard-tree-<n>` ConfigMaps of at
 * most [Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES] each (one ConfigMap cannot hold the whole
 * tree under the API's 1 MiB object limit), and one projected volume mounts every file read-only at
 * its `<folder>/<file>.json` path under [GRAFANA_DASHBOARD_ROOT] in the Grafana container, where the
 * file provider sweeps it. The ConfigMaps are applied with the Deployment, so `ConfigHashAnnotator`
 * hashes them and a dashboard change rolls Grafana.
 */
object GrafanaDashboardConfigMaps {
    /** The name every dashboard ConfigMap starts with, followed by its index. */
    const val NAME_PREFIX = "grafana-dashboard-tree-"

    private const val VOLUME = "dashboard-tree"
    private const val GRAFANA_CONTAINER = "grafana"

    /** A ConfigMap key may not hold `/`, so a folder separator is written as this. */
    private const val KEY_SEPARATOR = "__"

    /**
     * [resources] (Grafana's, as `GrafanaManifestBuilder` builds them) preceded by the ConfigMaps
     * holding [tree], with the Grafana container mounting them at [GRAFANA_DASHBOARD_ROOT].
     *
     * @param tree each dashboard's JSON keyed by its path relative to the tree root
     * @throws IllegalArgumentException when one dashboard alone exceeds the per-ConfigMap budget
     */
    fun mountInto(
        resources: List<HasMetadata>,
        tree: Map<String, String>,
    ): List<HasMetadata> {
        val deployment = resources.filterIsInstance<Deployment>().single()
        val groups = pack(tree)
        val maps = groups.mapIndexed { index, files -> configMap(index, files, deployment.metadata.namespace) }
        val mounted = mount(deployment, maps.zip(groups) { map, files -> map.metadata.name to files.keys })
        return maps + resources.map { if (it === deployment) mounted else it }
    }

    private fun keyOf(path: String): String = path.replace("/", KEY_SEPARATOR)

    private fun sizeOf(entry: Map.Entry<String, String>): Int = keyOf(entry.key).toByteArray().size + entry.value.toByteArray().size

    /** [tree] in catalog order, cut into consecutive groups that each fit the budget. */
    private fun pack(tree: Map<String, String>): List<Map<String, String>> {
        val budget = Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES
        val oversized = tree.entries.filter { sizeOf(it) > budget }.map { it.key }
        require(oversized.isEmpty()) {
            "These dashboards are each larger than one ConfigMap's $budget-byte budget: ${oversized.joinToString(", ")}."
        }
        return tree.entries.fold(listOf<Map<String, String>>()) { groups, entry ->
            val last = groups.lastOrNull()
            when {
                last != null && last.entries.sumOf(::sizeOf) + sizeOf(entry) <= budget -> groups.dropLast(1) + listOf(last + entry.toPair())
                else -> groups + listOf(mapOf(entry.toPair()))
            }
        }
    }

    private fun configMap(
        index: Int,
        files: Map<String, String>,
        namespace: String?,
    ): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("$NAME_PREFIX$index")
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/name", GRAFANA_CONTAINER)
            .endMetadata()
            .withData<String, String>(files.mapKeys { (path, _) -> keyOf(path) })
            .build()

    private fun mount(
        deployment: Deployment,
        maps: List<Pair<String, Set<String>>>,
    ): Deployment {
        val pod = deployment.spec.template.spec
        val sources =
            maps.map { (name, paths) ->
                VolumeProjectionBuilder()
                    .withNewConfigMap()
                    .withName(name)
                    .withItems(paths.map { KeyToPathBuilder().withKey(keyOf(it)).withPath(it).build() })
                    .endConfigMap()
                    .build()
            }
        val volume =
            VolumeBuilder()
                .withName(VOLUME)
                .withNewProjected()
                .withSources(sources)
                .endProjected()
                .build()
        val containers =
            pod.containers.map { container ->
                when (container.name) {
                    GRAFANA_CONTAINER ->
                        ContainerBuilder(container)
                            .addToVolumeMounts(
                                VolumeMountBuilder()
                                    .withName(VOLUME)
                                    .withMountPath(GRAFANA_DASHBOARD_ROOT)
                                    .withReadOnly(true)
                                    .build(),
                            ).build()
                    else -> container
                }
            }
        return DeploymentBuilder(deployment)
            .editSpec()
            .editTemplate()
            .editSpec()
            .withContainers(containers)
            .addToVolumes(volume)
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }
}
