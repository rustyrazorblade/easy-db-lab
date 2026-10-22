package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

/**
 * Finds the metrics, logs, traces and profile queries of a dashboard that read more than the
 * selected clusters.
 *
 * Clusters of one tenant share Mimir's, Loki's, Tempo's and Pyroscope's store, so a selector without
 * a `cluster` matcher mixes every cluster that ran in the dashboard's time range. A query is scoped
 * only when each of its selectors is, since `a{cluster=~"$cluster"} / b` still divides by every
 * cluster's `b`. A selector is scoped when it matches `cluster` against `$cluster`, or, on the pair
 * dashboards, against `$cluster_a` or `$cluster_b`. A TraceQL spanset is scoped when it matches
 * `resource.cluster` against one of them.
 */
object ClusterFilterGuards {
    /** Which backend a query is for. */
    enum class Language { PROMQL, LOGQL, PROFILES, TRACEQL }

    /**
     * One query of a dashboard: where it is and its text as the file holds it. A panel target also
     * carries its `legendFormat`, when it has one, and its panel's type.
     */
    data class Query(
        val where: String,
        val language: Language,
        val text: String,
        val legendFormat: String? = null,
        val panelType: String? = null,
    )

    /** The variables whose own query lists clusters or experiments, so it must read every cluster. */
    val CLUSTER_LISTS = DashboardVariables.CLUSTER_LISTS

    private val clusterVariables = DashboardVariables.CLUSTER_FILTERS.joinToString("|")
    private val clusterMatcher = Regex("""(^|[{,\s])cluster\s*=~?\s*"\$\{?($clusterVariables)(:regex)?\b""")
    private val spansetClusterMatcher = Regex("""(^|[{(\s&|])resource\.cluster\s*=~?\s*"\$\{?($clusterVariables)(:regex)?\b""")
    private val variableRegexMatcher = Regex("""\w+\s*=~\s*"\$\{?\w+(:\w+)?}?"""")
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
            // A TraceQL query is spansets joined by operators and followed by a pipeline; each `{...}` selects spans.
            Language.TRACEQL ->
                SelectorScanner(query.text, spansetClusterMatcher).scan().filter { it.braced && !it.scoped }.map { it.text }
        }

    /**
     * Every regex matcher of a profile [query] that reads a variable in Grafana's default format.
     * The Pyroscope plugin interpolates with that format, which writes a multi-value selection as the
     * glob `{a,b}`, so a matcher only works as `name=~"${'$'}{variable:regex}"`.
     */
    fun unformattedMatchers(query: Query): List<String> =
        if (query.language != Language.PROFILES) {
            emptyList()
        } else {
            variableRegexMatcher
                .findAll(query.text)
                .filter { it.groupValues[1] != ":regex" }
                .map { it.value }
                .toList()
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
    private fun selectors(text: String): List<Selector> = SelectorScanner(text, clusterMatcher).scan()

    /**
     * One pass over a query's text, collecting its selectors; [selectors] describes what it skips.
     * A `{...}` is scoped when [scopedBy] finds a cluster matcher in it.
     */
    private class SelectorScanner(
        private val text: String,
        private val scopedBy: Regex,
    ) {
        private val found = mutableListOf<Selector>()
        private var i = 0
        private var pendingName: String? = null

        fun scan(): List<Selector> {
            while (i < text.length) {
                val c = text[i]
                i =
                    when {
                        isQuote(c) -> skipString(text, i)
                        c == '[' -> skipGroup(text, i, '[', ']')
                        c == '{' -> braced()
                        startsNumber(c) -> number()
                        startsIdentifier(c) -> identifier()
                        else -> other(c)
                    }
            }
            flush()
            return found
        }

        private fun startsNumber(c: Char) = c.isDigit() || (c == '.' && text.getOrNull(i + 1)?.isDigit() == true)

        /** A `{...}` matcher list, joined to the metric name just before it, if any. */
        private fun braced(): Int {
            val end = skipGroup(text, i, '{', '}')
            val body = text.substring(i, end)
            found += Selector((pendingName ?: "") + body, scopedBy.containsMatchIn(body), braced = true)
            pendingName = null
            return end
        }

        private fun number(): Int {
            flush()
            return skipWhile(text, i) { it.isLetterOrDigit() || it == '.' }
        }

        /** A function, keyword, Grafana variable, label list, or the name of a metric selector. */
        private fun identifier(): Int {
            flush()
            val end = identifierEnd(text, i)
            val word = text.substring(i, end)
            val next = skipWhile(text, end) { it.isWhitespace() }
            val follows = text.getOrNull(next)
            return when {
                word in labelLists && follows == '(' -> skipGroup(text, next, '(', ')')
                follows == '(' || word in keywords || word.startsWith("$") -> end
                else -> {
                    pendingName = word
                    end
                }
            }
        }

        private fun other(c: Char): Int {
            if (!c.isWhitespace()) flush()
            return i + 1
        }

        /** Records the pending name as a bare metric selector, which has no matchers at all. */
        private fun flush() {
            pendingName?.let { found += Selector(it, scoped = false, braced = false) }
            pendingName = null
        }
    }

    internal fun isQuote(c: Char) = c == '"' || c == '\'' || c == '`'

    internal fun startsIdentifier(c: Char) = c.isLetter() || c == '_' || c == ':' || c == '$'

    private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '$'

    /** The end of an identifier that may hold Grafana variables: `a_${b}_c`, `$x`. */
    internal fun identifierEnd(
        text: String,
        start: Int,
    ): Int {
        var end = start
        var next = identifierStep(text, end)
        while (next > end) {
            end = next
            next = identifierStep(text, end)
        }
        return end
    }

    /** The index after the identifier piece at [i], one character or one `${...}`; [i] when there is none. */
    private fun identifierStep(
        text: String,
        i: Int,
    ): Int =
        when {
            i >= text.length -> i
            text.startsWith("\${", i) -> text.indexOf('}', i).let { if (it < 0) text.length else it + 1 }
            isIdentifierChar(text[i]) -> i + 1
            else -> i
        }

    internal fun skipWhile(
        text: String,
        start: Int,
        test: (Char) -> Boolean,
    ): Int {
        var i = start
        while (i < text.length && test(text[i])) i++
        return i
    }

    internal fun skipString(
        text: String,
        start: Int,
    ): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\' && quote != '`') 2 else 1
        return minOf(i + 1, text.length)
    }

    /** The index after the [close] that matches the [open] at [start], skipping strings inside. */
    internal fun skipGroup(
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
                isQuote(c) -> {
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
     * Every metrics, logs, traces and profile query of [dashboard]: panel targets (rows included),
     * Explore links (URL-decoded), annotation queries, and the queries of template variables. A
     * target with no datasource of its own uses its panel's, and a panel with none uses the default,
     * Mimir. A profile query is its label selector, and a profile variable the selector its profile
     * type makes; one with none reads every cluster and is reported as `{}`. A trace query is its
     * TraceQL, and a Tempo variable its filter, `{}` when it has none.
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
            if (language == Language.TRACEQL) {
                // A Tempo label-values variable lists over its TraceQL filter, and over every span without one.
                found += Query(where, language, (query as? JsonObject)?.string("query")?.takeIf { it.isNotBlank() } ?: NO_SELECTOR)
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
                val text =
                    when (language) {
                        Language.PROFILES -> target.string("labelSelector") ?: NO_SELECTOR
                        // A service map or other non-TraceQL Tempo target carries no query.
                        Language.TRACEQL -> target.string("query")
                        else -> target.string("expr")
                    }
                text?.takeIf { it.isNotBlank() }?.let {
                    found += Query("panel '$title'", language, it, target.string("legendFormat"), panel.string("type"))
                }
            }
            exploreQueries(panel).forEach { (ds, expr) ->
                languageOf(ds)?.let { found += Query("link on '$title'", it, expr) }
            }
            (panel["panels"] as? JsonArray)?.let { panels(it, null, found) }
        }
    }

    /** The datasource and expression of each query in the Explore links (`/explore?...panes=`) of [panel]. */
    private fun exploreQueries(panel: JsonObject): List<Pair<JsonElement?, String>> =
        linkUrls(panel)
            .filter { "/explore" in it && "panes=" in it }
            .flatMap { url -> paneQueries(url) }

    /** Every `url` string of [panel] and its links, without descending into a row's nested panels. */
    private fun linkUrls(panel: JsonObject): List<String> {
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
    }

    /** The datasource and query text of each query in the decoded `panes=` of an Explore [url]. */
    private fun paneQueries(url: String): List<Pair<JsonElement?, String>> {
        val panes = json.parseToJsonElement(URLDecoder.decode(url.substringAfter("panes="), Charsets.UTF_8)) as JsonObject
        return panes.values.filterIsInstance<JsonObject>().flatMap { pane ->
            (pane["queries"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { query ->
                // PromQL and LogQL in `expr`, a profile selector in `labelSelector`, TraceQL in `query`.
                (query.string("expr") ?: query.string("labelSelector") ?: query.string("query"))
                    ?.let { (query["datasource"] ?: pane["datasource"]) to it }
            }
        }
    }

    /** The language of each datasource type a dashboard names. */
    private val languageByType =
        mapOf(
            "prometheus" to Language.PROMQL,
            "loki" to Language.LOGQL,
            PYROSCOPE_TYPE to Language.PROFILES,
            "tempo" to Language.TRACEQL,
        )

    /** The language of each datasource a dashboard names by uid alone: a picker, or Pyroscope's fixed uid. */
    private val languageByUid =
        mapOf(
            "pyroscope" to Language.PROFILES,
            "\${${DashboardVariables.LOGS_DATASOURCE}}" to Language.LOGQL,
            "\${${DashboardVariables.TRACES_DATASOURCE}}" to Language.TRACEQL,
            "\${${DashboardVariables.METRICS_DATASOURCE}}" to Language.PROMQL,
        )

    /** The language of [datasource]: by its type when it has one, else by its uid; none at all is the default, Mimir. */
    private fun languageOf(datasource: JsonElement?): Language? {
        val (uid, type) =
            when (datasource) {
                is JsonObject -> datasource.string("uid") to datasource.string("type")
                is JsonPrimitive -> datasource.contentOrNull to null
                else -> null to null
            }
        return when {
            type != null -> languageByType[type]
            uid == null -> Language.PROMQL
            else -> languageByUid[uid]
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
