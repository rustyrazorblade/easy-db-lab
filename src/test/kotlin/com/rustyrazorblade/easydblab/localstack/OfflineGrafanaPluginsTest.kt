package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An opened export's Grafana downloads no plugin: with no network, the download fails and Grafana
 * exits. That takes nothing away only while no dashboard an export carries uses one of the plugins a
 * cluster's Grafana downloads. If a dashboard starts to use one, this fails, and the plugin has to be
 * carried by the export instead.
 */
class OfflineGrafanaPluginsTest {
    private val context = DashboardInstallContext(cluster = null, experiment = null, tenants = TenantSet.of("acme", listOf("acme")), documentsUrl = "http://127.0.0.1:3080")

    /** Every `type` (panel, datasource, query) named anywhere in [element]. */
    private fun types(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) -> (if (key == "type" && value is JsonPrimitive) listOf(value.content) else emptyList()) + types(value) }
            is JsonArray -> element.flatMap(::types)
            else -> emptyList()
        }

    @Test
    fun `no packaged dashboard uses a plugin Grafana downloads at start`() {
        val tree = PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()).render(context)
        val used = tree.mapValues { (_, json) -> types(Json.parseToJsonElement(json)).toSet() }

        assertThat(tree).isNotEmpty()
        assertThat(used.filterValues { it.any { type -> type in GrafanaServerSettings.DOWNLOADED_PLUGINS } }.keys)
            .describedAs("dashboards that need a plugin an offline Grafana cannot download")
            .isEmpty()
    }
}
