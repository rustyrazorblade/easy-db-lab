package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.Constants

/**
 * The rule every store prefix must follow, checked wherever a prefix enters the store model.
 *
 * A prefix leads every key and is joined into the compactor's IAM allow, so a prefix that holds an
 * IAM wildcard (`*`, `?`), climbs with `..`, or names one of the store's roots could widen that
 * allow onto data the compactor must never touch (`grafana/`, `reports/`, `pyroscope/`). A prefix
 * is therefore plain path segments only, and no segment is a root. Slashes around it are allowed
 * and dropped, as `ClusterStoragePath` drops them.
 */
object StorePrefix {
    private val SEGMENT = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*$")

    /** The roots no prefix segment may be: every root of [com.rustyrazorblade.easydblab.configuration.ObservabilityStore]. */
    val ROOTS =
        setOf(
            Constants.Observability.METRICS_ROOT,
            Constants.Observability.LOGS_ROOT,
            Constants.Observability.TRACES_ROOT,
            Constants.Observability.PROFILES_ROOT,
            Constants.Observability.GRAFANA_ROOT,
            Constants.Observability.REPORTS_ROOT,
        )

    /**
     * [prefix] when it follows the rule.
     *
     * @throws IllegalArgumentException naming [prefix] when a segment is empty, `.` or `..`, holds
     *   a character other than letters, digits, `.`, `_` and `-`, or is a store root.
     */
    fun requireValid(prefix: String): String {
        val trimmed = prefix.trim('/')
        if (trimmed.isEmpty()) return prefix
        trimmed.split('/').forEach { segment ->
            require(SEGMENT.matches(segment) && segment != "..") {
                "Invalid store prefix '$prefix': each segment must be letters, digits, '.', '_' or '-', " +
                    "with no wildcard, no empty segment and no '..'."
            }
            require(segment !in ROOTS) {
                "Invalid store prefix '$prefix': '$segment' is a store root (${ROOTS.sorted().joinToString()})."
            }
        }
        return prefix
    }
}
