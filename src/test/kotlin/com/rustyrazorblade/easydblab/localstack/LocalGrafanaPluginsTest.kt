package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A local stack's Grafana, live or offline, downloads no plugin. Offline, the download fails and
 * Grafana exits; live, the download from grafana.com stalled Grafana's start for minutes. That takes
 * nothing away only while every packaged dashboard a local stack installs (the core tree and every
 * kit's) needs no plugin the stack does not install. If a dashboard starts to use one of the plugins
 * a cluster's Grafana downloads, this fails for each mode that lacks it, and that stack has to carry
 * the plugin instead.
 */
class LocalGrafanaPluginsTest {
    companion object {
        @JvmStatic
        fun specs(): List<LocalStackSpec> =
            listOf(
                LocalStackSpec(
                    mode = LocalStackSpec.Mode.Live(bucket = "b", region = "eu-west-1", awsProfile = "p", home = "acme"),
                    tenants = listOf("acme"),
                ),
                LocalStackSpec(
                    mode = LocalStackSpec.Mode.Offline(dataDir = Constants.Export.DATA_DIR, home = "acme"),
                    tenants = listOf("acme"),
                ),
            )
    }

    /** Every `type` (panel, datasource, query) named anywhere in [element]. */
    private fun types(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> {
                val own = (element["type"] as? JsonPrimitive)?.let { listOf(it.content) }.orEmpty()
                own + element.values.flatMap(::types)
            }
            is JsonArray -> element.flatMap(::types)
            else -> emptyList()
        }

    /** The plugins the composed stack's Grafana installs at start. */
    private fun installedPlugins(spec: LocalStackSpec): Set<String> {
        val compose = ComposeFile.fromYaml(String(LocalStackComposer.compose(spec).getValue(Constants.LocalStack.COMPOSE_FILE)))
        val environment = compose.services.getValue(Constants.LocalStack.Services.GRAFANA).environment.orEmpty()
        return environment["GF_INSTALL_PLUGINS"]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toSet()
    }

    @ParameterizedTest
    @MethodSource("specs")
    fun `the stack's Grafana downloads no plugin at start`(spec: LocalStackSpec) {
        assertThat(installedPlugins(spec)).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("specs")
    fun `no packaged dashboard the stack installs needs a plugin the stack does not install`(spec: LocalStackSpec) {
        val context =
            DashboardInstallContext(
                cluster = null,
                experiment = null,
                tenants = spec.tenantSet,
                documentsUrl = "http://127.0.0.1:${spec.documentsPort}",
            )
        val tree = PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()).render(context)
        val missing = GrafanaServerSettings.DOWNLOADED_PLUGINS.toSet() - installedPlugins(spec)
        val used = tree.mapValues { (_, json) -> types(Json.parseToJsonElement(json)).toSet() }

        assertThat(tree).isNotEmpty()
        assertThat(used.filterValues { it.any { type -> type in missing } }.keys)
            .describedAs("dashboards that need a plugin a ${spec.mode::class.simpleName} local Grafana does not install")
            .isEmpty()
    }
}
