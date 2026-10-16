package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.services.DashboardRef
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitDashboardInstance
import com.rustyrazorblade.easydblab.services.installConfigYaml
import com.rustyrazorblade.easydblab.services.selectInstanceDashboards
import com.rustyrazorblade.easydblab.services.uidsInstalledElsewhere
import io.github.classgraph.ClassGraph
import java.io.File

/**
 * A dashboard tree a local stack's Grafana provisions: `<folder>/<file>.json` paths, each with its
 * JSON after the install-time pass ([GrafanaDashboardTreeWriter.render]) for the stack.
 */
interface DashboardTreeSource {
    /** What the operator is told the tree came from. */
    val origin: String

    /** Every dashboard of the tree, by `<folder>/<file>.json`, as installed for [context]. */
    fun render(context: DashboardInstallContext): Map<String, String>
}

/**
 * The install-time pass for a local stack's dashboards: a cluster's, with the stack's Pyroscope
 * address, which answers nothing until profiles join the stack (issue 1016).
 */
private fun renderForLocalStack(
    json: String,
    context: DashboardInstallContext,
): String = GrafanaDashboardTreeWriter.render(json, BackendUrls.LOCAL_COMPOSE.profiles, context)

/**
 * Any directory of `<folder>/<file>.json` dashboards, as `observability local dashboards --from`
 * names it: a dashboard edit is seen with no rebuild. Files that are not JSON are ignored; a JSON file
 * at the root or nested deeper has no Grafana folder, so it is refused rather than skipped.
 *
 * @property root the directory holding one subdirectory per Grafana folder.
 */
class DirectoryDashboardTree(
    private val root: File,
) : DashboardTreeSource {
    override val origin: String get() = root.path

    override fun render(context: DashboardInstallContext): Map<String, String> {
        require(root.isDirectory) { "$root is not a directory of dashboards" }
        val json =
            root
                .walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "json" }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()
        val misplaced = json.filter { it.split('/').size != 2 }
        require(misplaced.isEmpty()) {
            "Every dashboard must sit at <folder>/<name>.json, one directory below $root; these do not: " +
                misplaced.joinToString(", ")
        }
        return json.associateWith { renderForLocalStack(File(root, it).readText(), context) }
    }
}

/**
 * One packaged kit's dashboards, as every instance of the kit installs them on a cluster: the plain
 * kit in a folder named after the kit, and each extension's instance (`<kit>-<extension>`) in its own
 * folder, with the uids and scrape jobs [KitDashboardInstance] gives that instance.
 *
 * @property config the kit's `kit.yaml`.
 * @property files the kit's dashboard files, by path relative to the kit (`dashboards/<file>.json`).
 */
data class PackagedKitDashboards(
    val config: KitConfig,
    val files: Map<String, String>,
) {
    /** Every instance's dashboards, by `<instance>/<file>.json`, before the install-time pass. */
    fun instances(): Map<String, String> {
        val refs = config.dashboards.ifEmpty { files.keys.sorted().map { DashboardRef(it) } }
        val extensions = listOf("") + refs.map { it.extension }.filter { it.isNotBlank() }.distinct()
        return extensions
            .flatMap { extension ->
                val instance = if (extension.isBlank()) config.name else "${config.name}-$extension"
                val selected = selectInstanceDashboards(refs, extension).filter { it.path in files }
                val rendered =
                    KitDashboardInstance(
                        kitName = instance,
                        kitType = config.name,
                        dashboards = selected.map { files.getValue(it.path) },
                        elsewhere = uidsInstalledElsewhere(refs, extension) { files[it.path] },
                    ).rendered()
                selected.zip(rendered).map { (ref, json) -> "$instance/${ref.path.substringAfterLast('/')}" to json }
            }.toMap()
    }

    companion object {
        private const val KITS = "com/rustyrazorblade/easydblab/kits"
        private const val DASHBOARDS_DIR = "dashboards"

        /** Every kit packaged in the distribution that has dashboards, sorted by name. */
        fun discover(): List<PackagedKitDashboards> =
            ClassGraph()
                .acceptPaths(KITS)
                .scan()
                .use { scan ->
                    scan.allResources
                        .map { it.path.removePrefix("$KITS/") to it }
                        .filter { (path, _) -> path.split('/').size >= 2 }
                        .groupBy({ (path, _) -> path.substringBefore('/') }, { (path, resource) -> path.substringAfter('/') to resource })
                        .mapNotNull { (_, entries) ->
                            val byPath = entries.toMap()
                            val yaml = byPath[Constants.Kit.CONFIG_FILE] ?: return@mapNotNull null
                            val dashboards =
                                byPath
                                    .filterKeys { it.startsWith("$DASHBOARDS_DIR/") && it.endsWith(".json") }
                                    .mapValues { it.value.contentAsString }
                            if (dashboards.isEmpty()) return@mapNotNull null
                            PackagedKitDashboards(
                                installConfigYaml.decodeFromString(KitConfig.serializer(), yaml.contentAsString),
                                dashboards,
                            )
                        }
                }.sortedBy { it.config.name }
    }
}

/**
 * The dashboards packaged in the distribution: the core catalog and every packaged kit's, as a
 * Homebrew install has them, with no source checkout.
 *
 * @property catalog the core dashboards.
 * @property kits the packaged kits' dashboards.
 */
class PackagedDashboardTree(
    private val catalog: GrafanaDashboardCatalog,
    private val kits: List<PackagedKitDashboards>,
) : DashboardTreeSource {
    override val origin: String get() = "packaged dashboards"

    override fun render(context: DashboardInstallContext): Map<String, String> {
        val core =
            catalog.dashboards.associate { dashboard ->
                val resource = catalog.resourcePathOf(dashboard)
                val json =
                    PackagedDashboardTree::class.java
                        .getResourceAsStream(resource)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Dashboard resource not found: $resource")
                dashboard.relativePath to json
            }
        val kitDashboards = kits.flatMap { it.instances().entries }.associate { it.toPair() }
        val clashes = core.keys intersect kitDashboards.keys
        check(clashes.isEmpty()) { "A kit dashboard has the path of a core dashboard: ${clashes.joinToString(", ")}" }
        return (core + kitDashboards).mapValues { (_, json) -> renderForLocalStack(json, context) }
    }
}
