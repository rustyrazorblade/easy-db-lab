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
 * The uid each extension dashboard of [refs] that the instance with [extension] does not install
 * has where it is installed. Only the `<kit>-<extension>` instance installs an extension's
 * dashboard, under its uid suffixed with the extension (`postgres-duckdb` becomes
 * `postgres-duckdb-duckdb`), so a link to it from any other instance must name that uid. [read]
 * gives a dashboard's JSON, or null when its file is missing.
 */
fun uidsInstalledElsewhere(
    refs: List<DashboardRef>,
    extension: String,
    read: (DashboardRef) -> String?,
): Map<String, String> =
    refs
        .filter { it.extension.isNotBlank() && it.extension != extension }
        .mapNotNull { ref ->
            val uid = read(ref)?.let { (Json.parseToJsonElement(it).jsonObject["uid"] as? JsonPrimitive)?.contentOrNull }
            uid?.let { it to "$it-${ref.extension}" }
        }.toMap()

/**
 * A kit's dashboards made into one instance's own. Grafana uids are global, so a second instance
 * of a kit (postgres-duckdb beside postgres) installing a dashboard under the kit's uid would move
 * the first instance's dashboard into its own folder. The kit's own instance ([kitName] equal to
 * [kitType]) installs its [dashboards] unchanged; any other instance gets each uid suffixed with
 * what its name adds to the kit's (`postgres-overview` becomes `postgres-overview-duckdb`), links
 * between the dashboards it installs point at its own copies, and queries selecting the kit's
 * scrape job (`job="postgres"`) select the instance's (`job="postgres-duckdb"`): a kit's scrape
 * job is named after the instance that registered it. Links to a dashboard another instance
 * installs point at the uid in [elsewhere] (see [uidsInstalledElsewhere]).
 */
class KitDashboardInstance(
    private val kitName: String,
    private val kitType: String,
    private val dashboards: List<String>,
    private val elsewhere: Map<String, String> = emptyMap(),
) {
    private val isKitsOwnInstance = kitName == kitType
    private val suffix = kitName.removePrefix("$kitType-")

    /** Each dashboard's JSON as this instance installs it, in the order given. */
    fun rendered(): List<String> {
        if (isKitsOwnInstance && elsewhere.isEmpty()) return dashboards
        val parsed = dashboards.map { Json.parseToJsonElement(it).jsonObject }
        val renamed = if (isKitsOwnInstance) emptyMap() else parsed.mapNotNull { uidOf(it) }.associateWith { "$it-$suffix" }
        val links = elsewhere + renamed
        return parsed.map { dashboard ->
            val body = rewriteStrings(dashboard) { value -> ownJob(relink(value, links)) }.jsonObject
            val uid = uidOf(dashboard)
            val withUid = if (uid == null || uid !in renamed) body else JsonObject(body + ("uid" to JsonPrimitive(renamed.getValue(uid))))
            Json.encodeToString(JsonObject.serializer(), withUid)
        }
    }

    private fun uidOf(dashboard: JsonObject): String? = (dashboard["uid"] as? JsonPrimitive)?.contentOrNull

    /** Points every `/d/<uid>` link at a dashboard in [renamed] to the uid it is installed under. */
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
