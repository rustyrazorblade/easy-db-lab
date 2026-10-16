package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import com.rustyrazorblade.easydblab.localstack.LocalStackComposer
import com.rustyrazorblade.easydblab.localstack.LocalStackManifest
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.services.TemplateService

/**
 * Everything an export carries besides the copied data, by path relative to the stack directory, all
 * from resources packaged in the distribution:
 *
 * - the offline compose stack and its configuration ([LocalStackComposer] in [LocalStackSpec.Mode.Offline]);
 * - the dashboards under `dashboards/current`, after the install-time pass for the export: its
 *   tenant, no current cluster or experiment (each opens on the newest), and the documents view on
 *   loopback;
 * - `README.txt`: how to open it, the images the opening machine needs (exactly those the compose
 *   file names, each a `LocalImages` pin), and that profiles are left out (issue 1016);
 * - the manifest, so `observability local dashboards` works on the opened export.
 *
 * The zip carries no images (owner decision 15): the opening machine pulls them beforehand.
 *
 * @property dashboards the dashboards to install, the packaged ones.
 * @property toolVersion the version the manifest and the README record.
 * @property grafanaPort Grafana's port on `127.0.0.1` when the export is opened.
 * @property documentsPort the documents view's port on `127.0.0.1` when the export is opened.
 */
class ExportStackFiles(
    private val dashboards: DashboardTreeSource,
    private val toolVersion: String,
    private val grafanaPort: Int = Constants.LocalStack.DEFAULT_GRAFANA_PORT,
    private val documentsPort: Int = Constants.LocalStack.DEFAULT_DOCUMENTS_PORT,
) {
    private companion object {
        const val README_TEMPLATE = "README.txt"
        const val DASHBOARD_TREE = "${Constants.LocalStack.DASHBOARDS_DIR}/${Constants.LocalStack.DASHBOARDS_TREE}"
    }

    /** The stack files of an export of [tenant]; [root] is the directory the zip unpacks to. */
    fun files(
        tenant: String,
        root: String = tenant,
    ): Map<String, ByteArray> {
        val spec =
            LocalStackSpec(
                mode = LocalStackSpec.Mode.Offline(dataDir = Constants.Export.DATA_DIR, home = tenant),
                tenants = listOf(tenant),
                grafanaPort = grafanaPort,
                documentsPort = documentsPort,
            )
        val stack = LocalStackComposer.compose(spec)
        val owned = stack + (Constants.Export.README_FILE to readme(spec, stack, root).toByteArray())
        val context =
            DashboardInstallContext(
                cluster = null,
                experiment = null,
                tenants = TenantSet.of(tenant, listOf(tenant)),
                documentsUrl = "http://${Constants.LocalStack.LOOPBACK}:${spec.documentsPort}",
            )
        val tree = dashboards.render(context).map { (path, json) -> "$DASHBOARD_TREE/$path" to json.toByteArray() }.toMap()
        val manifest = LocalStackManifest.of(toolVersion, spec, owned).toJson().toByteArray()
        return owned + tree + (Constants.LocalStack.MANIFEST_FILE to manifest)
    }

    private fun readme(
        spec: LocalStackSpec,
        stack: Map<String, ByteArray>,
        root: String,
    ): String {
        val images =
            ComposeFile
                .fromYaml(String(stack.getValue(Constants.LocalStack.COMPOSE_FILE)))
                .services.values
                .map { it.image }
                .distinct()
                .sorted()
        return TemplateService
            .Template(CompactorTaskDefinition.resource(ExportStackFiles::class.java, README_TEMPLATE), emptyMap())
            .substitute(
                mapOf(
                    "TENANT" to spec.mode.home,
                    "TOOL_VERSION" to toolVersion,
                    "ROOT" to root,
                    "GRAFANA_URL" to "http://${Constants.LocalStack.LOOPBACK}:${spec.grafanaPort}",
                    "IMAGES" to images.joinToString("\n") { "    docker pull $it" },
                    "PROFILES_ISSUE" to Constants.Export.PROFILES_ISSUE.toString(),
                ),
            )
    }
}
