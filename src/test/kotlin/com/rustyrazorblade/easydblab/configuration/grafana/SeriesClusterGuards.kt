package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query

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
    fun droppedCluster(query: String): List<String> {
        val found = mutableListOf<String>()
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

    /** Records what the word at [start] drops, and returns the index to read on from. */
    private fun word(
        query: String,
        start: Int,
        found: MutableList<String>,
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
            if ((word == "by" && CLUSTER !in labels) || (word == "without" && CLUSTER in labels)) {
                found += "$word (${labels.joinToString(", ")})"
            }
            return close
        }
        if (word in aggregations && ungrouped(query, next)) found += word
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
     * draws no series, so its legend is not read. `{{cluster_name}}` needs the query's
     * `label_replace` that writes `cluster_name`, or the legend shows nothing for it.
     */
    fun legendProblems(query: Query): List<String> {
        val legend = query.legendFormat ?: return emptyList()
        val logLines = query.language == Language.LOGQL && query.text.trim().startsWith("{")
        return when {
            logLines || query.language == Language.PROFILES -> emptyList()
            !legendCluster.containsMatchIn(legend) -> listOf("legend '$legend' does not show the cluster")
            "cluster_name" in legend && !shortName.containsMatchIn(query.text) ->
                listOf("legend '$legend' reads cluster_name, which the query does not write")
            else -> emptyList()
        }
    }

    private const val CLUSTER = "cluster"
}
