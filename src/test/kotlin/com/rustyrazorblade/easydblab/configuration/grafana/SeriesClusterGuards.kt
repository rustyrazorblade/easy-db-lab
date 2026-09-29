package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the dashboard series that lose their cluster, so that two selected clusters draw as one line.
 *
 * Every cluster names its hosts the same way, so an aggregation that drops `cluster` adds `db0` of
 * one cluster to `db0` of another, and a legend without the cluster leaves two lines that cannot be
 * told apart. Every aggregation keeps `cluster`, and every legend shows it: as `{{cluster_name}}`,
 * the short name the query's `label_replace` cuts from `<name>-<uuid>`, or as `{{cluster}}`.
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
    private val legendCluster = Regex("""\{\{\s*(cluster|cluster_name)\s*}}""")
    private val shortName = Regex(""""cluster_name"\s*,\s*"\$1"\s*,\s*"cluster"""")
    private val clusterVariable = Regex("""cluster\s*=~?\s*"\$\{?cluster\b""")
    private val sideVariable = Regex("""\$\{?(baseline_cluster|candidate_cluster)\b""")

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
            !legendCluster.containsMatchIn(legend) -> listOf("legend '$legend' does not show the cluster")
            "cluster_name" in legend && !shortName.containsMatchIn(query.text) ->
                listOf("legend '$legend' reads cluster_name, which the query does not write")
            else -> emptyList()
        }
    }

    /**
     * Why a table [panel] whose queries write `cluster_name` does not show the cluster as one short
     * column: its `organize` must hide the long `cluster` column (`cluster`, or `cluster 1` once a
     * join numbers the frames) and name the `cluster_name` column "Cluster".
     */
    fun tableProblems(panel: JsonObject): List<String> {
        if ((panel["type"] as? JsonPrimitive)?.contentOrNull != TABLE) return emptyList()
        val targets = (panel["targets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (targets.none { shortName.containsMatchIn((it["expr"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }) return emptyList()
        val organize =
            (panel["transformations"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == "organize" }
        val options = organize?.get("options") as? JsonObject
        val excluded = (options?.get("excludeByName") as? JsonObject).orEmpty()
        val renamed = (options?.get("renameByName") as? JsonObject).orEmpty()
        val hidden = listOf(CLUSTER, "$CLUSTER 1").any { (excluded[it] as? JsonPrimitive)?.contentOrNull == "true" }
        val shown = listOf("cluster_name", "cluster_name 1").any { (renamed[it] as? JsonPrimitive)?.contentOrNull == "Cluster" }
        return listOfNotNull(
            "the long cluster column is not hidden".takeUnless { hidden },
            "cluster_name is not shown as Cluster".takeUnless { shown },
        )
    }

    /**
     * Each join of a [panel] (`seriesToColumns` or `joinByField`) whose key does not hold the
     * cluster. Every cluster names its hosts and pods the same way, so a join on `instance` alone
     * puts one cluster's row beside another's; the key is a label that joins the cluster to the
     * instance, such as `cluster_instance`.
     */
    fun joinProblems(panel: JsonObject): List<String> =
        (panel["transformations"] as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { (it["id"] as? JsonPrimitive)?.contentOrNull in setOf("seriesToColumns", "joinByField") }
            .mapNotNull { join ->
                val key = ((join["options"] as? JsonObject)?.get("byField") as? JsonPrimitive)?.contentOrNull.orEmpty()
                "${(join["id"] as JsonPrimitive).content} joins on '$key', which does not hold the cluster".takeUnless { CLUSTER in key }
            }

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
    private const val TABLE = "table"
}
