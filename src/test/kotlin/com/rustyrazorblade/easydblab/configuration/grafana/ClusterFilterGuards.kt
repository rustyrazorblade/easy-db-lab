package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

/**
 * Finds the metrics, logs and profile queries of a dashboard that read more than the selected clusters.
 *
 * Clusters of one tenant share Mimir's, Loki's and Pyroscope's store, so a selector without a `cluster` matcher
 * mixes every cluster that ran in the dashboard's time range. A query is scoped only when each of
 * its selectors is, since `a{cluster=~"$cluster"} / b` still divides by every cluster's `b`. A
 * selector is scoped when it matches `cluster` against `$cluster`, or, on the comparison
 * dashboards, against `$baseline_cluster` or `$candidate_cluster`.
 */
object ClusterFilterGuards {
    /** Which backend a query is for. */
    enum class Language { PROMQL, LOGQL, PROFILES }

    /** One query of a dashboard: where it is and its text as the file holds it. */
    data class Query(
        val where: String,
        val language: Language,
        val text: String,
    )

    /** The variables whose own query lists clusters, so it must read every cluster. */
    val CLUSTER_LISTS = setOf("cluster", "baseline_cluster", "candidate_cluster")

    private val clusterMatcher =
        Regex("""(^|[{,\s])cluster\s*=~?\s*"\$\{?(cluster|baseline_cluster|candidate_cluster)(:regex)?\b""")
    private val labelValues = Regex("""^label_values\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Words that stand where a bare metric name could: operators, modifiers, and the aggregations,
     * which may be followed by `by (...)` rather than by their argument.
     */
    private val keywords =
        setOf("and", "or", "unless", "bool", "offset", "by", "without", "on", "ignoring", "group_left", "group_right", "inf", "nan") +
            setOf(
                "sum",
                "min",
                "max",
                "avg",
                "group",
                "stddev",
                "stdvar",
                "count",
                "count_values",
                "bottomk",
                "topk",
                "quantile",
                "limitk",
                "limit_ratio",
            )
    private val labelLists = setOf("by", "without", "on", "ignoring", "group_left", "group_right")

    /** Every selector of [query] with no cluster matcher: its label set, or a bare metric name. */
    fun unscoped(query: Query): List<String> =
        when (query.language) {
            // A LogQL pipeline is full of bare words (`json`, `unwrap x`); only `{...}` selects streams.
            // A Pyroscope label selector is one `{...}`.
            Language.LOGQL, Language.PROFILES -> selectors(query.text).filter { it.braced && !it.scoped }.map { it.text }
            Language.PROMQL -> unscopedPromQl(query.text)
        }

    private fun unscopedPromQl(text: String): List<String> {
        val trimmed = text.trim()
        val values = labelValues.find(trimmed) ?: return selectors(trimmed).filterNot { it.scoped }.map { it.text }
        // label_values(selector, label) lists over the selector; label_values(label) lists over everything.
        val args = values.groupValues[1]
        val comma = args.lastIndexOf(',')
        return if (comma < 0) listOf(trimmed) else unscopedPromQl(args.substring(0, comma))
    }

    private data class Selector(
        val text: String,
        val scoped: Boolean,
        val braced: Boolean,
    )

    /**
     * The vector selectors (PromQL) or stream selectors (LogQL) of [text]. Strings, ranges and the
     * label lists of `by (...)` and its kin are skipped; an identifier followed by `(` is a function.
     */
    private fun selectors(text: String): List<Selector> {
        val found = mutableListOf<Selector>()
        var i = 0
        var pendingName: String? = null
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i)
                c == '[' -> i = skipGroup(text, i, '[', ']')
                c == '{' -> {
                    val end = skipGroup(text, i, '{', '}')
                    val body = text.substring(i, end)
                    found += Selector((pendingName ?: "") + body, clusterMatcher.containsMatchIn(body), braced = true)
                    pendingName = null
                    i = end
                }
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    pendingName = flush(pendingName, found)
                    i = skipWhile(text, i) { it.isLetterOrDigit() || it == '.' }
                }
                c.isLetter() || c == '_' || c == ':' || c == '$' -> {
                    pendingName = flush(pendingName, found)
                    val end = identifierEnd(text, i)
                    val word = text.substring(i, end)
                    val next = skipWhile(text, end) { it.isWhitespace() }
                    val follows = text.getOrNull(next)
                    i =
                        when {
                            word in labelLists && follows == '(' -> skipGroup(text, next, '(', ')')
                            follows == '(' || word in keywords || word.startsWith("$") -> end
                            else -> {
                                pendingName = word
                                end
                            }
                        }
                }
                else -> {
                    if (!c.isWhitespace()) pendingName = flush(pendingName, found)
                    i++
                }
            }
        }
        flush(pendingName, found)
        return found
    }

    /** Records [name] as a bare metric selector, which has no matchers at all. */
    private fun flush(
        name: String?,
        found: MutableList<Selector>,
    ): String? {
        if (name != null) found += Selector(name, scoped = false, braced = false)
        return null
    }

    /** The end of an identifier that may hold Grafana variables: `a_${b}_c`, `$x`. */
    private fun identifierEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            i =
                when {
                    text.startsWith("\${", i) -> text.indexOf('}', i).let { if (it < 0) text.length else it + 1 }
                    c.isLetterOrDigit() || c == '_' || c == ':' || c == '$' -> i + 1
                    else -> return i
                }
        }
        return i
    }

    private fun skipWhile(
        text: String,
        start: Int,
        test: (Char) -> Boolean,
    ): Int {
        var i = start
        while (i < text.length && test(text[i])) i++
        return i
    }

    private fun skipString(
        text: String,
        start: Int,
    ): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\' && quote != '`') 2 else 1
        return minOf(i + 1, text.length)
    }

    /** The index after the [close] that matches the [open] at [start], skipping strings inside. */
    private fun skipGroup(
        text: String,
        start: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    i = skipString(text, i)
                    continue
                }
                c == open -> depth++
                c == close -> if (--depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    /**
     * Every metrics, logs and profile query of [dashboard]: panel targets (rows included), Explore
     * links, annotation queries, and the queries of template variables. A target with no datasource
     * of its own uses its panel's, and a panel with none uses the default, Mimir. A profile query is
     * its label selector, and a profile variable the selector its profile type makes; one with none
     * reads every cluster and is reported as `{}`.
     */
    fun queries(dashboard: JsonObject): List<Query> {
        val found = mutableListOf<Query>()
        (dashboard["panels"] as? JsonArray)?.let { panels(it, null, found) }
        objects(dashboard["annotations"], "list").forEach { annotation ->
            val language = languageOf(annotation["datasource"]) ?: return@forEach
            annotation.string("expr")?.let { found += Query("annotation '${annotation.string("name")}'", language, it) }
        }
        objects(dashboard["templating"], "list").forEach { variable ->
            if (variable.string("type") != "query") return@forEach
            val language = languageOf(variable["datasource"]) ?: return@forEach
            val where = "variable '${variable.string("name")}'"
            val query = variable["query"]
            if (language == Language.PROFILES) {
                found += Query(where, language, profileVariableSelector(query as? JsonObject))
                return@forEach
            }
            val texts =
                listOfNotNull(
                    variable.string("definition"),
                    (query as? JsonPrimitive)?.contentOrNull,
                    (query as? JsonObject)?.string("query"),
                    (query as? JsonObject)?.string("stream"),
                ).filter { it.isNotBlank() }
            texts.forEach { found += Query(where, language, it) }
        }
        return found
    }

    /**
     * The selector the Pyroscope plugin lists a variable's values over. Its `VariableSupport`
     * builds `{__profile_type__="<profileTypeId>"}` and never reads a `labelSelector` on a
     * variable, so any other matcher has to be written into the profile type.
     */
    private fun profileVariableSelector(query: JsonObject?): String =
        query?.string("profileTypeId")?.let { "{__profile_type__=\"$it\"}" } ?: NO_SELECTOR

    private fun panels(
        panels: JsonArray,
        inherited: JsonElement?,
        found: MutableList<Query>,
    ) {
        panels.filterIsInstance<JsonObject>().forEach { panel ->
            val title = panel.string("title").orEmpty()
            val datasource = panel["datasource"] ?: inherited
            (panel["targets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { target ->
                val language = languageOf(target["datasource"] ?: datasource) ?: return@forEach
                val text = if (language == Language.PROFILES) target.string("labelSelector") ?: NO_SELECTOR else target.string("expr")
                text?.takeIf { it.isNotBlank() }?.let { found += Query("panel '$title'", language, it) }
            }
            exploreQueries(panel).forEach { (ds, expr) ->
                languageOf(ds)?.let { found += Query("link on '$title'", it, expr) }
            }
            (panel["panels"] as? JsonArray)?.let { panels(it, null, found) }
        }
    }

    /** The datasource and expression of each query in the Explore links (`/explore?...panes=`) of [panel]. */
    private fun exploreQueries(panel: JsonObject): List<Pair<JsonElement?, String>> {
        val urls = mutableListOf<String>()

        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject ->
                    element.forEach { (key, value) ->
                        if (key == "panels") return@forEach
                        val url = (value as? JsonPrimitive)?.takeIf { key == "url" && it.isString }?.content
                        if (url != null) urls += url else collect(value)
                    }
                is JsonArray -> element.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(panel)
        return urls
            .filter { "/explore" in it && "panes=" in it }
            .flatMap { url ->
                val panes = json.parseToJsonElement(URLDecoder.decode(url.substringAfter("panes="), Charsets.UTF_8)) as JsonObject
                panes.values.filterIsInstance<JsonObject>().flatMap { pane ->
                    (pane["queries"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { query ->
                        (query.string("expr") ?: query.string("labelSelector"))?.let { (query["datasource"] ?: pane["datasource"]) to it }
                    }
                }
            }
    }

    private fun languageOf(datasource: JsonElement?): Language? {
        val (uid, type) =
            when (datasource) {
                is JsonObject -> datasource.string("uid") to datasource.string("type")
                is JsonPrimitive -> datasource.contentOrNull to null
                else -> null to null
            }
        return when {
            type == "prometheus" -> Language.PROMQL
            type == "loki" -> Language.LOGQL
            type == PYROSCOPE_TYPE -> Language.PROFILES
            type != null -> null
            uid == "pyroscope" -> Language.PROFILES
            uid == "\${logs_datasource}" -> Language.LOGQL
            uid == null || uid == "\${metrics_datasource}" -> Language.PROMQL
            else -> null
        }
    }

    private fun objects(
        parent: JsonElement?,
        key: String,
    ): List<JsonObject> = ((parent as? JsonObject)?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val PYROSCOPE_TYPE = "grafana-pyroscope-datasource"

    /** The selector of a profile query that has none: it reads every cluster. */
    private const val NO_SELECTOR = "{}"
}
