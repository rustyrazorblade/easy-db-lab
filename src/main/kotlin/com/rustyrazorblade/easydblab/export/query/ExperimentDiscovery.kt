package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.configuration.ExperimentKey

/**
 * No cluster of [key]'s tenant carries the experiment.
 *
 * @property query the lookup that found none.
 */
class ExperimentNotFoundException(
    val key: ExperimentKey,
    val query: String,
) : QueryExportRefused("Experiment ${key.name} has no clusters in tenant ${key.tenant}: $query found none")

/** Values for a PromQL regex matcher. */
object PromQlValues {
    /** The regex characters RE2 gives a meaning to. */
    private val SPECIAL = Regex("""[\\.+*?()|\[\]{}^$]""")

    /**
     * [values] as one regex alternation inside a PromQL double-quoted string: each regex character is
     * escaped, and the escape is itself escaped for the string.
     */
    fun anyOf(values: List<String>): String = values.joinToString("|") { value -> SPECIAL.replace(value) { "\\\\${it.value}" } }
}

/**
 * Finds what a query export covers, with the pre-label branch (design section 8.2): a cluster from
 * before the `experiment` label is an experiment of one, named by its own `<name>-<id>`.
 *
 * @property mimir the reader's Mimir.
 */
class ExperimentDiscovery(
    private val mimir: MimirApi,
) {
    /**
     * Every cluster of [key] in its tenant: each labelled `experiment=<name>`, and a pre-label cluster
     * whose `<name>-<id>` is the name. Same-named experiments of other tenants are never read.
     *
     * @throws ExperimentNotFoundException when there are none.
     */
    fun clustersOf(key: ExperimentKey): List<String> {
        val matches = listOf("up{experiment=\"${key.name}\"}", "up{experiment=\"\",cluster=\"${key.name}\"}")
        val clusters = mimir.labelValues(listOf(key.tenant), CLUSTER, matches)
        if (clusters.isEmpty()) {
            throw ExperimentNotFoundException(key, "label values of $CLUSTER for ${matches.joinToString(" or ")} in tenant ${key.tenant}")
        }
        return clusters
    }

    /** The experiments [clusters] of [tenant] belong to: their labelled experiments, then each pre-label cluster's own id. */
    fun coveredBy(
        tenant: String,
        clusters: List<String>,
    ): List<ExperimentKey> {
        val selector = "cluster=~\"${PromQlValues.anyOf(clusters)}\""
        val labelled = mimir.labelValues(listOf(tenant), EXPERIMENT, listOf("up{$selector}"))
        val preLabel = mimir.labelValues(listOf(tenant), CLUSTER, listOf("up{$selector,experiment=\"\"}"))
        return (labelled + preLabel).distinct().map { ExperimentKey(tenant, it) }
    }

    private companion object {
        const val CLUSTER = "cluster"
        const val EXPERIMENT = "experiment"
    }
}
