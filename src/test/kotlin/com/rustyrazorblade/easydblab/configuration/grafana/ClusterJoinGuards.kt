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

    private fun labels(list: String): List<String> = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private const val HOST = "host_name"
    private const val CLUSTER = "cluster"
}
