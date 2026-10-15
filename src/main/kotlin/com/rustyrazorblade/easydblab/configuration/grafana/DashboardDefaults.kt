package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the install-time pass needs to know about where a dashboard is installed.
 *
 * On a cluster every field is set. The live local stack has no current cluster and no current
 * experiment, so both are null and every open shows the newest experiment; an export has no
 * current cluster but may have the experiment it was taken of.
 *
 * @property cluster The current cluster's `cluster` label, `<name>-<id>`, or null where there is none
 * @property experiment The current experiment, or null where there is none
 * @property tenants Every tenant in the shared store, and the home tenant as [TenantSet.home]
 * @property documentsUrl Base URL of the documents web server, as the browser reaches it
 */
data class DashboardInstallContext(
    val cluster: String?,
    val experiment: ExperimentKey?,
    val tenants: TenantSet,
    val documentsUrl: String,
)

/** The base URL of the documents web server on [controlHost], as the browser reaches it. */
fun documentsUrl(controlHost: ClusterHost): String = "http://${controlHost.privateIp}:${Constants.Grafana.Documents.WEB_PORT}"

/**
 * The one install-time pass every dashboard goes through on its way to Grafana, whichever path
 * installs it: the core tree, a kit `start`, `grafana install`, the local stack, or an export.
 *
 * The dashboard files store no defaults, because a default names a cluster, an experiment or a
 * tenant and the files are shared by every cluster. This pass sets the `current` value, and for a
 * custom variable the options (a custom variable's `query` is its option list), of the variables it
 * owns, for the place being installed:
 *
 * - each datasource picker's `current` becomes the stable datasource of its type (`mimir`, `loki`,
 *   `tempo`), which reads the home tenant;
 * - `cluster` defaults to the current cluster, as a one-element list when the variable is
 *   multi-select, except on a dashboard that declares `experiment`, where it keeps no default and
 *   opens on "All", the experiment's clusters;
 * - `cluster_b` defaults to the current cluster; `cluster_a` gets no default, so it opens on the
 *   experiment's oldest cluster;
 * - `experiment` defaults to the current experiment's `<tenant>/<name>`;
 * - the hidden `home_tenant` gets one option, the home tenant, and selects it;
 * - [DOCUMENTS_URL_PLACEHOLDER] in any string becomes the documents web server's URL.
 *
 * Where [DashboardInstallContext] has no cluster or no experiment, the variables that would take it
 * are left as the file has them. No query, variable query or panel changes, and a dashboard with
 * none of these variables comes out equal to what went in.
 */
object DashboardDefaults {
    /** Placeholder a dashboard carries where the documents web server's base URL belongs. */
    const val DOCUMENTS_URL_PLACEHOLDER = "__DOCUMENTS_URL__"

    /** The variables that select a cluster and default to the current one. */
    val CLUSTER_VARIABLES = setOf(DashboardVariables.CLUSTER, DashboardVariables.CLUSTER_B)

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
        val experimentDashboard = DashboardVariables.EXPERIMENT in variableNames(dashboard)
        val withVariables = mapVariables(dashboard) { variable -> defaulted(variable, context, experimentDashboard) }
        return replacePlaceholder(withVariables, context.documentsUrl) as JsonObject
    }

    private fun variableNames(dashboard: JsonObject): Set<String> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("name") }
            .toSet()

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
        experimentDashboard: Boolean,
    ): JsonObject {
        val name = variable.string("name")
        val cluster = context.cluster
        val experiment = context.experiment
        // On an experiment dashboard `cluster` lists the experiment's clusters and opens on "All".
        val takesCluster = name in CLUSTER_VARIABLES && !(experimentDashboard && name == DashboardVariables.CLUSTER)
        return when {
            variable.string("type") == "datasource" -> pickerDefault(variable)
            takesCluster && cluster != null ->
                variable + ("current" to current(cluster, variable.isMulti(), text = clusterShortName(cluster)))
            name == DashboardVariables.EXPERIMENT && experiment != null ->
                variable + ("current" to current(experiment.pickerValue, multi = false))
            name == DashboardVariables.HOME_TENANT -> homeTenant(variable, context.tenants.home)
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

    /** The hidden `home_tenant`, with [home] as its one option and its selection. */
    private fun homeTenant(
        variable: JsonObject,
        home: String,
    ): Map<String, JsonElement> =
        variable +
            mapOf(
                "query" to JsonPrimitive(home),
                "options" to
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("selected", true)
                                put("text", home)
                                put("value", home)
                            },
                        )
                    },
                "current" to current(home, multi = false),
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
