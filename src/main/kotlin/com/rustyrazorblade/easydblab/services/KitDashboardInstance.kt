package com.rustyrazorblade.easydblab.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The dashboards a kit instance installs: every one with no extension, plus those of the
 * [extension] the instance was created with. The plain kit (no extension) installs only the
 * extension-free ones.
 */
fun selectInstanceDashboards(
    refs: List<DashboardRef>,
    extension: String,
): List<DashboardRef> = refs.filter { it.extension.isBlank() || it.extension == extension }

/**
 * A kit's dashboards made into one instance's own. Grafana uids are global, so a second instance
 * of a kit (postgres-duckdb beside postgres) installing a dashboard under the kit's uid would move
 * the first instance's dashboard into its own folder. The kit's own instance ([kitName] equal to
 * [kitType]) installs its [dashboards] unchanged; any other instance gets each uid suffixed with
 * what its name adds to the kit's (`postgres-overview` becomes `postgres-overview-duckdb`), links
 * between the dashboards it installs point at its own copies, and queries selecting the kit's
 * scrape job (`job="postgres"`) select the instance's (`job="postgres-duckdb"`): a kit's scrape
 * job is named after the instance that registered it.
 */
class KitDashboardInstance(
    private val kitName: String,
    private val kitType: String,
    private val dashboards: List<String>,
) {
    private val isKitsOwnInstance = kitName == kitType
    private val suffix = kitName.removePrefix("$kitType-")

    /** Each dashboard's JSON as this instance installs it, in the order given. */
    fun rendered(): List<String> {
        if (isKitsOwnInstance) return dashboards
        val parsed = dashboards.map { Json.parseToJsonElement(it).jsonObject }
        val renamed = parsed.mapNotNull { uidOf(it) }.associateWith { "$it-$suffix" }
        return parsed.map { dashboard ->
            val body = rewriteStrings(dashboard) { value -> ownJob(relink(value, renamed)) }.jsonObject
            val uid = uidOf(dashboard)
            val withUid = if (uid == null) body else JsonObject(body + ("uid" to JsonPrimitive(renamed.getValue(uid))))
            Json.encodeToString(JsonObject.serializer(), withUid)
        }
    }

    private fun uidOf(dashboard: JsonObject): String? = (dashboard["uid"] as? JsonPrimitive)?.contentOrNull

    /** Points every `/d/<uid>` link at a dashboard in [renamed] to its renamed copy. */
    private fun relink(
        value: String,
        renamed: Map<String, String>,
    ): String =
        renamed.entries.fold(value) { text, (from, to) ->
            text.replace(Regex("/d/${Regex.escape(from)}(?![A-Za-z0-9_-])"), "/d/$to")
        }

    /** Makes a selector of the kit's scrape job select this instance's. */
    private fun ownJob(value: String): String = value.replace("job=\"$kitType\"", "job=\"$kitName\"")

    private fun rewriteStrings(
        element: JsonElement,
        transform: (String) -> String,
    ): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> rewriteStrings(value, transform) })
            is JsonArray -> JsonArray(element.map { rewriteStrings(it, transform) })
            is JsonPrimitive -> if (element.isString) JsonPrimitive(transform(element.content)) else element
        }
}
