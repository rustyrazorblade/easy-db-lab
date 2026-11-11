package com.rustyrazorblade.easydblab.configuration.grafana

/**
 * Finds the PromQL vector matches that pair one cluster's host with another cluster's host.
 *
 * Every cluster names its hosts the same way (db0, app0, control0), so `host_name` alone does not
 * identify a host once the `cluster` variable selects more than one cluster. A one-to-one match
 * `on (host_name)` then fails with "found duplicate series for the match group", and `and on
 * (host_name)` quietly keeps one cluster's series for another cluster's reason. A match names
 * `cluster` with `host_name`, never ignores `cluster`, and every aggregation that feeds it keeps
 * `cluster`, or the match has nothing to pair on.
 */
object ClusterJoinGuards {
    private val labelList = Regex("""\b(on|ignoring|by)\s*\(([^)]*)\)""")

    /** Each problem in [promQl]: the clause and why it pairs hosts across clusters. */
    fun problems(promQl: String): List<String> {
        val clauses = labelList.findAll(promQl).map { it.groupValues[1] to labels(it.groupValues[2]) }.toList()
        val joinsOnHost = clauses.any { (keyword, labels) -> keyword == "on" && HOST in labels }
        return clauses.mapNotNull { (keyword, labels) ->
            val clause = "$keyword (${labels.joinToString(", ")})"
            when {
                keyword == "on" && HOST in labels && CLUSTER !in labels -> "$clause matches hosts without their cluster"
                keyword == "ignoring" && CLUSTER in labels -> "$clause matches across clusters"
                keyword == "by" && joinsOnHost && HOST in labels && CLUSTER !in labels -> "$clause drops the cluster a match needs"
                else -> null
            }
        }
    }

    /**
     * Each one-to-one operation in [promQl] between two selectors of one metric that fix different
     * values of one label, with no `on` or `ignoring`: every pair of series differs in that label,
     * so the match finds no pair and the panel is always empty. Each side may sit inside functions
     * that keep the labels (`rate`, `irate`, ...) and carry a range and an `offset`; an aggregation
     * on either side, or an `on`/`ignoring` between them, changes what pairs, and is left alone.
     */
    fun unmatchableSelectors(promQl: String): List<String> =
        sameMetricOperation
            .findAll(promQl)
            .filterNot { match -> functionNames(match.groupValues[1] + match.groupValues[5]).any { it in pairingChangers } }
            .flatMap { match ->
                val metric = match.groupValues[2]
                val left = equalities(match.groupValues[3])
                val right = equalities(match.groupValues[6])
                left.keys.filter { it in right && left[it] != right[it] }.map { label ->
                    "$metric{$label=\"${left[label]}\"} ${match.groupValues[4]} $metric{$label=\"${right[label]}\"} never pair"
                }
            }.toList()

    private fun functionNames(prefix: String): List<String> =
        Regex("""([A-Za-z_]+)\s*\(""").findAll(prefix).map { it.groupValues[1] }.toList()

    private fun equalities(matchers: String): Map<String, String> =
        equality.findAll(matchers).associate { it.groupValues[1] to it.groupValues[2] }

    private fun labels(list: String): List<String> = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** One side: functions around a selector, with an optional range, offset and closing parentheses. */
    private const val TAIL = """(?:\[[^\]]*\])?(?:\s+offset\s+-?\w+)?(?:\s*\))*(?:\s+offset\s+-?\w+)?"""
    private val sameMetricOperation =
        Regex("""((?:[A-Za-z_]+\s*\(\s*)*)([A-Za-z_:][\w:]*)\{([^}]*)\}$TAIL\s*([/*+-])\s*((?:[A-Za-z_]+\s*\(\s*)*)\2\{([^}]*)\}""")

    /** Aggregations and vector-matching keywords: either changes which series pair. */
    private val pairingChangers =
        setOf(
            "sum",
            "avg",
            "min",
            "max",
            "count",
            "group",
            "stddev",
            "stdvar",
            "topk",
            "bottomk",
            "quantile",
            "count_values",
            "limitk",
            "limit_ratio",
            "on",
            "ignoring",
            "group_left",
            "group_right",
        )
    private val equality = Regex("""\b(\w+)\s*=\s*"([^"]*)"""")

    private const val HOST = "host_name"
    private const val CLUSTER = "cluster"
}
