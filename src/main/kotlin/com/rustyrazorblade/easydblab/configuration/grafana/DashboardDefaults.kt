package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the install-time pass needs to know about the cluster a dashboard is installed on.
 *
 * @property cluster The cluster's `cluster` label, `<name>-<id>`
 * @property tenants Every tenant in the shared store, and the cluster's own as [TenantSet.home]
 * @property documentsUrl Base URL of the documents web server, as the browser reaches it
 */
data class DashboardInstallContext(
    val cluster: String,
    val tenants: TenantSet,
    val documentsUrl: String,
)

/** The base URL of the documents web server on [controlHost], as the browser reaches it. */
fun documentsUrl(controlHost: ClusterHost): String = "http://${controlHost.privateIp}:${Constants.Grafana.Documents.WEB_PORT}"

/**
 * The one install-time pass every dashboard goes through on its way to Grafana, whichever path
 * installs it: the core tree, a kit `start`, or `grafana install`.
 *
 * The dashboard files store no defaults, because a default names a cluster or a tenant and the
 * files are shared by every cluster. This pass fills them in for the cluster being installed:
 *
 * - each datasource picker's `current` becomes the stable datasource of its type (`mimir`, `loki`,
 *   `tempo`), which reads the cluster's own tenant;
 * - `cluster`, `baseline_cluster` and `candidate_cluster` default to the current cluster, as a
 *   one-element list when the variable is multi-select;
 * - `doc_tenant` offers every tenant and defaults to the cluster's own;
 * - [DOCUMENTS_URL_PLACEHOLDER] in any string becomes the documents web server's URL.
 *
 * It sets only those values. No query, variable query or panel changes, and a dashboard with none
 * of these variables comes out equal to what went in.
 */
object DashboardDefaults {
    /** Placeholder a dashboard carries where the documents web server's base URL belongs. */
    const val DOCUMENTS_URL_PLACEHOLDER = "__DOCUMENTS_URL__"

    /** The variables that select a cluster and default to the current one. */
    val CLUSTER_VARIABLES = setOf("cluster", "baseline_cluster", "candidate_cluster")

    /** The variable that selects the tenant folder of the documents. */
    const val DOC_TENANT_VARIABLE = "doc_tenant"

    /** The stable datasource, as uid and name, that a picker of each type defaults to. */
    private val stableDatasources =
        mapOf(
            "prometheus" to (Constants.Grafana.DatasourceUid.MIMIR to "Mimir"),
            "loki" to (Constants.Grafana.DatasourceUid.LOKI to "Loki"),
            "tempo" to (Constants.Grafana.DatasourceUid.TEMPO to "Tempo"),
        )

    /** [dashboard] with the defaults of [context] filled in. */
    fun apply(
        dashboard: JsonObject,
        context: DashboardInstallContext,
    ): JsonObject {
        val withVariables = mapVariables(dashboard) { variable -> defaulted(variable, context) }
        return replacePlaceholder(withVariables, context.documentsUrl) as JsonObject
    }

    private fun mapVariables(
        dashboard: JsonObject,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val templating = dashboard["templating"] as? JsonObject ?: return dashboard
        val list = templating["list"] as? JsonArray ?: return dashboard
        val mapped = JsonArray(list.map { (it as? JsonObject)?.let(transform) ?: it })
        return JsonObject(dashboard + ("templating" to JsonObject(templating + ("list" to mapped))))
    }

    private fun defaulted(
        variable: JsonObject,
        context: DashboardInstallContext,
    ): JsonObject {
        val name = variable.string("name")
        val type = variable.string("type")
        return when {
            type == "datasource" -> pickerDefault(variable)
            name in CLUSTER_VARIABLES ->
                variable + ("current" to current(context.cluster, variable.isMulti(), text = clusterShortName(context.cluster)))
            name == DOC_TENANT_VARIABLE -> docTenant(variable, context.tenants)
            else -> variable
        }.let(::JsonObject)
    }

    private fun pickerDefault(variable: JsonObject): Map<String, JsonElement> {
        val (uid, name) = stableDatasources[variable.string("query")] ?: return variable
        return variable +
            (
                "current" to
                    buildJsonObject {
                        put("selected", true)
                        put("text", name)
                        put("value", uid)
                    }
            )
    }

    private fun docTenant(
        variable: JsonObject,
        tenants: TenantSet,
    ): Map<String, JsonElement> =
        variable +
            mapOf(
                "query" to JsonPrimitive(tenants.all.joinToString(",")),
                "options" to
                    buildJsonArray {
                        tenants.all.forEach { tenant ->
                            add(
                                buildJsonObject {
                                    put("selected", tenant == tenants.home)
                                    put("text", tenant)
                                    put("value", tenant)
                                },
                            )
                        }
                    },
                "current" to current(tenants.home, multi = false),
            )

    /**
     * A variable's selection of [value], shown as [text]. A cluster picker shows the short name and
     * selects the full id, as its options do.
     */
    private fun current(
        value: String,
        multi: Boolean,
        text: String = value,
    ): JsonObject =
        buildJsonObject {
            put("selected", true)
            if (multi) {
                put("text", buildJsonArray { add(JsonPrimitive(text)) })
                put("value", buildJsonArray { add(JsonPrimitive(value)) })
            } else {
                put("text", text)
                put("value", value)
            }
        }

    /**
     * The short name of the cluster [id] `<name>-<uuid>`: the name and the first 8 characters of the
     * id, as the cluster pickers' option text and every legend show it. An id that is not a
     * `<name>-<uuid>` is its own short name.
     */
    fun clusterShortName(id: String): String = SHORT_NAME.matchEntire(id)?.groupValues?.get(1) ?: id

    /** The name and the first 8 characters of the id; the rest of the UUID is dropped. */
    private val SHORT_NAME = Regex("""(.+?)-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    private fun replacePlaceholder(
        element: JsonElement,
        url: String,
    ): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> replacePlaceholder(value, url) })
            is JsonArray -> JsonArray(element.map { replacePlaceholder(it, url) })
            is JsonPrimitive ->
                if (element.isString && DOCUMENTS_URL_PLACEHOLDER in element.content) {
                    JsonPrimitive(element.content.replace(DOCUMENTS_URL_PLACEHOLDER, url))
                } else {
                    element
                }
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.isMulti(): Boolean = (this["multi"] as? JsonPrimitive)?.contentOrNull == "true"
}
