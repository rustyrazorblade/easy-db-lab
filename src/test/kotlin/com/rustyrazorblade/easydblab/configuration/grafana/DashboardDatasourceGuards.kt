package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The three rules that keep a dashboard switchable between tenants, as pure checks on its JSON.
 *
 * A dashboard names its datasources only through the pickers (`metrics_datasource`,
 * `logs_datasource`, `traces_datasource`), declares a picker for each signal it uses, and passes
 * its pickers and selected clusters on every link to another dashboard. A fixed `mimir`, `loki` or
 * `tempo` uid anywhere, or a link that drops a picker, pins part of the dashboard to the cluster's
 * own tenant while the rest follows the picker. Each check returns what it found, so a test can
 * report every problem in a file at once and the checks themselves can be tested on small samples.
 */
object DashboardDatasourceGuards {
    /** The stable datasource uids that a dashboard must reach only through a picker. */
    val FIXED_UIDS = setOf("mimir", "loki", "tempo")

    /** The picker a dashboard declares for each datasource type it uses. */
    val PICKERS = mapOf("prometheus" to "metrics_datasource", "loki" to "logs_datasource", "tempo" to "traces_datasource")

    /** The variables a `/d/` link must pass when its source dashboard declares them. */
    val CARRIED =
        listOf(
            "metrics_datasource",
            "logs_datasource",
            "traces_datasource",
            "cluster",
            "baseline_cluster",
            "candidate_cluster",
            "doc_tenant",
        )

    private val encodedUidRef = Regex(""""(uid|datasource)"\s*:\s*"(mimir|loki|tempo)"""")
    private val encodedTypeRef = Regex(""""type"\s*:\s*"(prometheus|loki|tempo)"""")
    private val queryParam = Regex("""\$\{([A-Za-z0-9_]+):queryparam}""")
    private val percentRun = Regex("(%[0-9A-Fa-f]{2})+")

    /** The JSON path of every place [dashboard] names a fixed uid, URL-encoded links included. */
    fun fixedUids(dashboard: JsonElement): List<String> {
        val found = mutableListOf<String>()
        walk(dashboard, "$") { path, key, value ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@walk
            if ((key == "uid" || key == "datasource") && text in FIXED_UIDS) found += path
            if (isLinkUrl(text) && encodedUidRef.containsMatchIn(urlDecode(text))) found += path
        }
        return found
    }

    /** Each datasource type [dashboard] uses without declaring its picker, as `type (picker)`. */
    fun missingPickers(dashboard: JsonObject): List<String> {
        val declared =
            variables(dashboard)
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "datasource" }
                .mapNotNull { it["query"]?.jsonPrimitive?.contentOrNull }
                .toSet()
        val used = mutableSetOf<String>()
        walk(dashboard, "$") { _, key, value ->
            if (key == "datasource" && value is JsonObject) {
                value["type"]?.jsonPrimitive?.contentOrNull?.let { used += it }
            }
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text != null && isLinkUrl(text)) {
                encodedTypeRef.findAll(urlDecode(text)).forEach { used += it.groupValues[1] }
            }
        }
        return PICKERS.filterKeys { it in used && it !in declared }.map { (type, picker) -> "$type ($picker)" }
    }

    /**
     * Every rule a `/d/` link in [dashboard] breaks: a carried variable the dashboard declares and
     * the link does not pass, or a `${name:queryparam}` for a variable the dashboard does not declare.
     */
    fun linkViolations(dashboard: JsonObject): List<String> {
        val declared = variables(dashboard).mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }.toSet()
        val required = CARRIED.filter { it in declared }
        val found = mutableListOf<String>()
        walk(dashboard, "$") { _, key, value ->
            val url = (value as? JsonPrimitive)?.takeIf { key == "url" && it.isString }?.content ?: return@walk
            if ("/d/" !in url) return@walk
            required
                .filterNot { "\${$it:queryparam}" in url || "var-$it=" in url }
                .forEach { found += "$url: does not pass $it" }
            queryParam
                .findAll(url)
                .map { it.groupValues[1] }
                .filterNot { it in declared }
                .forEach { found += "$url: passes undeclared $it" }
        }
        return found
    }

    private fun variables(dashboard: JsonObject): List<JsonObject> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().map { it.jsonObject }

    private fun isLinkUrl(text: String): Boolean = "/explore" in text || "panes=" in text

    /** Percent-decodes [text] and leaves `+` as it is, since a query may hold a literal plus. */
    private fun urlDecode(text: String): String =
        percentRun.replace(text) { run ->
            val bytes =
                run.value
                    .split("%")
                    .filter { it.isNotEmpty() }
                    .map { it.toInt(HEX).toByte() }
                    .toByteArray()
            String(bytes, Charsets.UTF_8)
        }

    private const val HEX = 16

    private fun walk(
        element: JsonElement,
        path: String,
        visit: (path: String, key: String, value: JsonElement) -> Unit,
    ) {
        when (element) {
            is JsonObject ->
                element.forEach { (key, value) ->
                    val child = "$path.$key"
                    visit(child, key, value)
                    walk(value, child, visit)
                }
            is JsonArray -> element.forEachIndexed { i, value -> walk(value, "$path[$i]", visit) }
            else -> Unit
        }
    }
}
