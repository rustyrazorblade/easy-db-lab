package com.rustyrazorblade.easydblab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLDecoder

/**
 * Every query the dashboard tree sends to a datasource, with Grafana's variables filled in, so a
 * test can run each one against the pinned backend.
 *
 * Panels (and panels nested in rows), dashboard variables, annotation queries and the Explore links
 * built into data links are all read. A variable is replaced the way Grafana would with a plausible
 * value: a custom or interval variable by its current or first option, anything else by a value
 * that is valid wherever a label value or a regex may stand.
 */
object DashboardQueries {
    /** Which backend a query is for. */
    enum class Language { LOGQL, PROMQL }

    /** One query: where it came from and its text with variables substituted. */
    data class Query(
        val source: String,
        val language: Language,
        val text: String,
        /** A variable's `label_values(selector, label)`: run as a label-values request, not a query. */
        val labelValues: Pair<String, String>? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The core dashboards and every kit's dashboards. */
    fun files(): List<File> {
        val core = File("dashboards").walkTopDown().filter { it.isFile && it.extension == "json" }
        val kits =
            File("src/main/resources/com/rustyrazorblade/easydblab/kits")
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" && it.parentFile.name == "dashboards" }
        return (core + kits).sortedBy { it.path }.toList()
    }

    /** Every query of [language] in every dashboard. */
    fun all(language: Language): List<Query> = files().flatMap { queries(it) }.filter { it.language == language }

    private fun queries(file: File): List<Query> {
        val dashboard = json.parseToJsonElement(file.readText()).jsonObject
        val variables = variableValues(dashboard)
        val found = mutableListOf<Query>()

        fun add(
            where: String,
            datasource: JsonElement?,
            text: String?,
        ) {
            val language = languageOf(datasource) ?: return
            if (text.isNullOrBlank()) return
            found += Query("${file.path} $where", language, substitute(text, variables))
        }

        walkPanels(dashboard) { panel ->
            val title = panel["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            (panel["targets"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { target ->
                add("panel '$title'", target["datasource"] ?: panel["datasource"], target["expr"]?.jsonPrimitive?.contentOrNull)
            }
            exploreLinks(panel).forEach { (datasource, expr) -> add("link on '$title'", datasource, expr) }
        }
        annotations(dashboard).forEach {
            add("annotation '${it["name"]?.jsonPrimitive?.contentOrNull}'", it["datasource"], it["expr"]?.jsonPrimitive?.contentOrNull)
        }
        templating(dashboard).forEach { variable ->
            val language = languageOf(variable["datasource"]) ?: return@forEach
            val definition = variable["definition"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val match = Regex("""^label_values\((.*),\s*([A-Za-z0-9_.]+)\)$""").find(definition.trim())
            val name = variable["name"]?.jsonPrimitive?.contentOrNull
            when {
                match != null ->
                    found +=
                        Query(
                            "${file.path} variable '$name'",
                            language,
                            definition,
                            substitute(match.groupValues[1], variables) to match.groupValues[2],
                        )
                definition.startsWith("query_result(") ->
                    found +=
                        Query(
                            "${file.path} variable '$name'",
                            language,
                            substitute(definition.removePrefix("query_result(").removeSuffix(")"), variables),
                        )
            }
        }
        return found
    }

    private fun languageOf(datasource: JsonElement?): Language? {
        val (uid, type) =
            when (datasource) {
                is JsonObject -> datasource["uid"]?.jsonPrimitive?.contentOrNull to datasource["type"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> datasource.contentOrNull to null
                else -> null to null
            }
        return when {
            uid == "loki" || type == "loki" -> Language.LOGQL
            uid == "mimir" || type == "prometheus" || uid == "\${datasource}" -> Language.PROMQL
            // A panel with no datasource uses the default, which is Mimir.
            uid == null && type == null -> Language.PROMQL
            else -> null
        }
    }

    private fun walkPanels(
        element: JsonElement,
        visit: (JsonObject) -> Unit,
    ) {
        when (element) {
            is JsonObject -> {
                if ("targets" in element || element["fieldConfig"] != null) visit(element)
                element.forEach { (key, value) -> if (key != "templating" && key != "annotations") walkPanels(value, visit) }
            }
            is JsonArray -> element.forEach { walkPanels(it, visit) }
            else -> Unit
        }
    }

    /** The datasource and expression of every Explore link (`/explore?...panes=`) on [panel]. */
    private fun exploreLinks(panel: JsonObject): List<Pair<JsonElement?, String?>> {
        val urls = mutableListOf<String>()

        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject ->
                    element.forEach { (key, value) ->
                        if (key == "url" &&
                            value is JsonPrimitive
                        ) {
                            urls += value.content
                        } else {
                            collect(value)
                        }
                    }
                is JsonArray -> element.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(panel)
        return urls
            .filter { it.startsWith("/explore") && "panes=" in it }
            .flatMap { url ->
                val panes = json.parseToJsonElement(URLDecoder.decode(url.substringAfter("panes="), Charsets.UTF_8)).jsonObject
                panes.values.map { it.jsonObject }.flatMap { pane ->
                    (pane["queries"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
                        it["datasource"] to
                            it["expr"]?.jsonPrimitive?.contentOrNull
                    }
                }
            }.filter { it.second != null }
    }

    private fun annotations(dashboard: JsonObject): List<JsonObject> =
        ((dashboard["annotations"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().map { it.jsonObject }

    private fun templating(dashboard: JsonObject): List<JsonObject> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().map { it.jsonObject }

    /** The value each of the dashboard's variables stands for in a query. */
    private fun variableValues(dashboard: JsonObject): Map<String, String> =
        templating(dashboard).associate { variable ->
            val name = variable["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val type = variable["type"]?.jsonPrimitive?.contentOrNull
            val current = (variable["current"] as? JsonObject)?.get("value")
            val currentValue =
                when (current) {
                    is JsonPrimitive -> current.contentOrNull
                    is JsonArray -> current.firstOrNull()?.jsonPrimitive?.contentOrNull
                    else -> null
                }?.takeUnless { it.startsWith("$") }
            val firstOption =
                variable["query"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
            name to
                when (type) {
                    "custom", "interval", "constant" -> currentValue ?: firstOption ?: "x"
                    "textbox" -> currentValue ?: ""
                    else -> "x"
                }
        }

    /** [text] with Grafana's built-in and dashboard variables replaced the way Grafana would. */
    fun substitute(
        text: String,
        variables: Map<String, String> = emptyMap(),
    ): String {
        var out = text
        val builtIns =
            mapOf(
                "__rate_interval" to "1m",
                "__interval" to "1m",
                "__interval_ms" to "60000",
                "__auto" to "1m",
                "__range" to "1h",
                "__range_s" to "3600",
                "__range_ms" to "3600000",
            )
        builtIns.forEach { (name, value) ->
            out = out.replace("\${$name}", value).replace(Regex("\\$$name\\b"), value)
        }
        out = out.replace(Regex("""\$\{__[a-zA-Z_.]+}"""), "x")
        out =
            out.replace(Regex("""\$\{([A-Za-z0-9_]+)(?::([a-z]+))?}""")) { m ->
                variables[m.groupValues[1]] ?: "x"
            }
        out = out.replace(Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")) { m -> variables[m.groupValues[1]] ?: "x" }
        return out
    }
}
