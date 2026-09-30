package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the dashboard series that lose their cluster, so that two selected clusters draw as one line.
 *
 * Every cluster names its hosts the same way, so an aggregation that drops `cluster` adds `db0` of
 * one cluster to `db0` of another, and a legend without the cluster leaves two lines that cannot be
 * told apart. Every aggregation keeps `cluster`, and every legend shows it as `{{cluster_name}}`,
 * the short name the query's `label_replace` cuts from `<name>-<uuid>`. A raw `{{cluster}}` shows
 * the whole `<name>-<uuid>`, so it does not count.
 *
 * A few queries read one cluster, or every cluster, on purpose and are [exempt].
 */
object SeriesClusterGuards {
    private val aggregations =
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
    private val grouping = setOf("by", "without")
    private val legendCluster = Regex("""\{\{\s*cluster_name\s*}}""")
    private val shortName = Regex(""""cluster_name"\s*,\s*"\$1"\s*,\s*"cluster"""")
    private val clusterVariable = Regex("""cluster\s*=~?\s*"\$\{?cluster\b""")
    private val sideVariable = Regex("""\$\{?(baseline_cluster|candidate_cluster)\b""")
    private val labelFunction = Regex("""\blabel_(join|replace)\s*\(""")
    private val vectorFallback = Regex("""\bor\s+vector\s*\(""")
    private val joinTransformations = setOf("seriesToColumns", "joinByField")
    private val numberingTransformations = joinTransformations + "concatenate"

    /**
     * The queries that read one cluster, or every cluster, on purpose: the variables that list
     * clusters, the Tests dashboard's listing, the comparison views' baseline and candidate run
     * queries (one cluster per side), and the AWS/S3 queries, which count the shared bucket once
     * across clusters (`SharedBucketCountTest`).
     */
    fun exempt(
        query: Query,
        testsListing: Boolean,
    ): Boolean =
        query.where.removePrefix("variable '").removeSuffix("'") in ClusterFilterGuards.CLUSTER_LISTS ||
            (testsListing && query.where.startsWith("panel ")) ||
            "aws_s3_" in query.text ||
            (sideVariable.containsMatchIn(query.text) && !clusterVariable.containsMatchIn(query.text))

    /**
     * Each aggregation of a PromQL or LogQL [query] that drops `cluster`: a `by (...)` without it, a
     * `without (...)` that names it, or an aggregation with no grouping at all, which keeps no label.
     */
    fun droppedCluster(query: String): List<String> =
        clauses(query).mapNotNull { clause ->
            when (clause) {
                is Clause.LabelList ->
                    clause.text.takeIf {
                        (clause.keyword == "by" && CLUSTER !in clause.labels) || (clause.keyword == "without" && CLUSTER in clause.labels)
                    }
                is Clause.Ungrouped -> clause.aggregation
            }
        }

    /**
     * Each `by (...)` of [query] that names one label twice, once [variables] (each variable's
     * possible values) fill in its variables: `by (cluster, $groupby)` with `cluster` among the
     * values of `groupby` groups by `cluster` twice.
     */
    fun repeatedGrouping(
        query: String,
        variables: Map<String, List<String>>,
    ): List<String> =
        clauses(query).filterIsInstance<Clause.LabelList>().filter { it.keyword == "by" }.mapNotNull { clause ->
            val (references, literals) = clause.labels.partition { it.startsWith("$") }
            val choices = references.map { variables[it.trim('$', '{', '}').substringBefore(':')].orEmpty().toSet() }
            val repeated =
                literals.size != literals.toSet().size ||
                    choices.any { values -> values.any { it in literals } } ||
                    choices.indices.any { i -> (i + 1 until choices.size).any { j -> choices[i].intersect(choices[j]).isNotEmpty() } }
            clause.text.takeIf { repeated }
        }

    /** A grouping clause of a query, or an aggregation called without one. */
    private sealed interface Clause {
        data class LabelList(
            val keyword: String,
            val labels: List<String>,
        ) : Clause {
            val text get() = "$keyword (${labels.joinToString(", ")})"
        }

        data class Ungrouped(
            val aggregation: String,
        ) : Clause
    }

    /** The label lists and ungrouped aggregations of [query], skipping strings, selectors and ranges. */
    private fun clauses(query: String): List<Clause> {
        val found = mutableListOf<Clause>()
        var i = 0
        while (i < query.length) {
            val c = query[i]
            i =
                when {
                    ClusterFilterGuards.isQuote(c) -> ClusterFilterGuards.skipString(query, i)
                    c == '[' -> ClusterFilterGuards.skipGroup(query, i, '[', ']')
                    c == '{' -> ClusterFilterGuards.skipGroup(query, i, '{', '}')
                    c.isDigit() -> ClusterFilterGuards.skipWhile(query, i) { it.isLetterOrDigit() || it == '.' }
                    ClusterFilterGuards.startsIdentifier(c) -> word(query, i, found)
                    else -> i + 1
                }
        }
        return found
    }

    /** Records the clause the word at [start] opens, if any, and returns the index to read on from. */
    private fun word(
        query: String,
        start: Int,
        found: MutableList<Clause>,
    ): Int {
        val end = ClusterFilterGuards.identifierEnd(query, start)
        val word = query.substring(start, end)
        val next = ClusterFilterGuards.skipWhile(query, end) { it.isWhitespace() }
        if (word in labelLists && query.getOrNull(next) == '(') {
            val close = ClusterFilterGuards.skipGroup(query, next, '(', ')')
            val labels =
                query
                    .substring(next + 1, close - 1)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            found += Clause.LabelList(word, labels)
            return close
        }
        if (word in aggregations && ungrouped(query, next)) found += Clause.Ungrouped(word)
        return end
    }

    /**
     * Whether the aggregation whose arguments start at [next] is called with neither `by` nor
     * `without`, before its arguments or after them. A word not followed by `(` is not a call.
     */
    private fun ungrouped(
        query: String,
        next: Int,
    ): Boolean {
        if (query.getOrNull(next) != '(') return false
        val close = ClusterFilterGuards.skipGroup(query, next, '(', ')')
        return wordAt(query, ClusterFilterGuards.skipWhile(query, close) { it.isWhitespace() }) !in grouping
    }

    private fun wordAt(
        query: String,
        at: Int,
    ): String =
        if (at < query.length && ClusterFilterGuards.startsIdentifier(query[at])) {
            query.substring(at, ClusterFilterGuards.identifierEnd(query, at))
        } else {
            ""
        }

    /**
     * Why the legend of a panel [query] hides the cluster; empty when it shows it. A log query
     * draws no series, so its legend is not read. A table's legend names a value column, so a
     * table shows the cluster as a column instead ([tableProblems]). `{{cluster_name}}` needs the
     * query's `label_replace` that writes `cluster_name`, or the legend shows nothing for it.
     */
    fun legendProblems(query: Query): List<String> {
        val legend = query.legendFormat ?: return emptyList()
        val logLines = query.language == Language.LOGQL && query.text.trim().startsWith("{")
        return when {
            logLines || query.language == Language.PROFILES || query.panelType == TABLE -> emptyList()
            !legendCluster.containsMatchIn(legend) -> listOf("legend '$legend' does not show the cluster by its short name")
            "cluster_name" in legend && !shortName.containsMatchIn(query.text) ->
                listOf("legend '$legend' reads cluster_name, which the query does not write")
            else -> emptyList()
        }
    }

    /**
     * Why a table [panel] whose queries write `cluster_name` does not show the cluster as one short
     * column: its `organize` must hide the long `cluster` column and name the `cluster_name` column
     * "Cluster". A join or a concatenation of N targets numbers each frame's columns, so it must hide `cluster 1` to
     * `cluster N` and `cluster_name 2` to `cluster_name N`, and name `cluster_name 1` "Cluster".
     */
    fun tableProblems(panel: JsonObject): List<String> {
        if (panel.string("type") != TABLE) return emptyList()
        val targets = targets(panel)
        if (targets.none { shortName.containsMatchIn(it.string("expr")) }) return emptyList()
        val options = transformations(panel).firstOrNull { it.string("id") == "organize" }?.get("options") as? JsonObject
        val excluded = (options?.get("excludeByName") as? JsonObject).orEmpty()
        val renamed = (options?.get("renameByName") as? JsonObject).orEmpty()
        val numbered = transformations(panel).any { it.string("id") in numberingTransformations }
        val frames = if (numbered && targets.size > 1) 1..targets.size else null

        fun column(
            label: String,
            frame: Int,
        ) = if (frames == null) label else "$label $frame"

        fun isHidden(column: String) = excluded.string(column) == "true"
        val each = frames ?: 1..1
        return listOfNotNull(
            "the long cluster column is not hidden".takeUnless { each.all { isHidden(column(CLUSTER, it)) } },
            "cluster_name is not shown as Cluster".takeUnless { renamed.string(column(CLUSTER_NAME, 1)) == "Cluster" },
        ) +
            each
                .drop(1)
                .map { column(CLUSTER_NAME, it) }
                .filterNot(::isHidden)
                .map { "$it is not hidden" }
    }

    /**
     * Each join of a [panel] (`seriesToColumns` or `joinByField`) whose key does not hold the
     * cluster. Every cluster names its hosts and pods the same way, so a join on `instance` alone
     * puts one cluster's row beside another's. The key is a label that joins the cluster to the
     * instance, such as `cluster_instance`. Every target writes it with `label_join` or
     * `label_replace`, and builds it from the long `cluster`, because two clusters can share a
     * short `cluster_name`.
     */
    fun joinProblems(panel: JsonObject): List<String> =
        joins(panel).flatMap { join ->
            val id = join.string("id")
            val key = (join["options"] as? JsonObject)?.string("byField").orEmpty()
            listOfNotNull("$id joins on '$key', which does not hold the cluster".takeUnless { CLUSTER in key }) +
                targets(panel).mapNotNull { target ->
                    val sources = labelWrites(target.string("expr"))[key]
                    val refId = target.string("refId")
                    when {
                        sources == null -> "$id: target $refId does not write '$key'"
                        CLUSTER !in sources -> "$id: target $refId builds '$key' from $sources, not from cluster"
                        else -> null
                    }
                }
        }

    /**
     * The labels [query] writes with `label_join` or `label_replace`, each with the labels it is
     * built from. When a query writes one label twice, the last write counts.
     */
    private fun labelWrites(query: String): Map<String, List<String>> =
        labelFunction
            .findAll(query)
            .mapNotNull { match ->
                val open = match.range.last
                val args =
                    arguments(query.substring(open + 1, ClusterFilterGuards.skipGroup(query, open, '(', ')') - 1))
                        .map { it.trim().removeSurrounding("\"") }
                when {
                    args.size <= SOURCES -> null
                    match.groupValues[1] == "join" -> args[1] to args.drop(SOURCES)
                    else -> args[1] to listOf(args[SOURCES])
                }
            }.toMap()

    /** The top-level, comma-separated arguments of a call whose argument list is [text]. */
    private fun arguments(text: String): List<String> {
        val args = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ',') {
                args += text.substring(start, i)
                start = i + 1
            }
            i =
                when {
                    ClusterFilterGuards.isQuote(c) -> ClusterFilterGuards.skipString(text, i)
                    c == '(' -> ClusterFilterGuards.skipGroup(text, i, '(', ')')
                    c == '{' -> ClusterFilterGuards.skipGroup(text, i, '{', '}')
                    c == '[' -> ClusterFilterGuards.skipGroup(text, i, '[', ']')
                    else -> i + 1
                }
        }
        return args + text.substring(start)
    }

    /**
     * Each `or vector(...)` of a [query] that groups by `cluster`. `vector(0)` has no labels, so it
     * supplies no zero for a cluster that has no series, and it adds a zero that has no cluster.
     * Take the zero from a series that has the cluster: `or 0 * sum by (cluster) (<total>)`.
     */
    fun clusterlessZero(query: String): List<String> {
        val byCluster = clauses(query).any { it is Clause.LabelList && it.keyword == "by" && CLUSTER in it.labels }
        return vectorFallback
            .findAll(query)
            .filter { byCluster }
            .map { "or vector(...) supplies a zero with no cluster" }
            .toList()
    }

    private fun joins(panel: JsonObject): List<JsonObject> = transformations(panel).filter { it.string("id") in joinTransformations }

    private fun transformations(panel: JsonObject): List<JsonObject> =
        (panel["transformations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun targets(panel: JsonObject): List<JsonObject> = (panel["targets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun Map<String, JsonElement>.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * Each field override of a series panel that matched a legend before the cluster was put in
     * front of it, and no longer does: `byName "baseline"` stops matching "db-a baseline". A legend
     * is read with every `{{label}}` as a sample value.
     */
    fun staleOverrides(
        legends: List<String>,
        matchers: List<Pair<String, String>>,
    ): List<String> {
        fun sample(legend: String) = legend.replace(Regex("""\{\{[^}]*}}"""), "x")
        val pairs = legends.map { sample(it.replaceFirst(Regex("""^\{\{\s*cluster_name\s*}}\s*"""), "")) to sample(it) }
        return matchers.mapNotNull { (id, pattern) ->
            val matcher = nameMatcher(id, pattern) ?: return@mapNotNull null
            "$id '$pattern'".takeIf { pairs.any { (before, after) -> matcher(before) && !matcher(after) } }
        }
    }

    /** How Grafana tests a field name against a `byName` or `byRegexp` matcher; null for any other matcher. */
    private fun nameMatcher(
        id: String,
        pattern: String,
    ): ((String) -> Boolean)? {
        val regex = if (id == "byRegexp") runCatching { Regex(pattern) }.getOrNull() else null
        return when {
            id == "byName" -> { name: String -> name == pattern }
            regex != null -> { name: String -> regex.containsMatchIn(name) }
            else -> null
        }
    }

    private const val CLUSTER = "cluster"
    private const val CLUSTER_NAME = "cluster_name"

    /** The index of the first source label in `label_join(v, dst, sep, src...)` and `label_replace(v, dst, repl, src, re)`. */
    private const val SOURCES = 3
    private const val TABLE = "table"
}
