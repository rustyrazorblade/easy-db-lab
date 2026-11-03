package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants

/**
 * How one series of a streamed metric's result becomes a sample entity.
 *
 * The catalogue's queries return one series per host, one series for the whole cluster, or the
 * two ends of a per-node range; each scope reads the matching entity from the series' labels.
 */
sealed interface MetricScope {
    /** The entity for a series with [labels], or null when the series cannot be named. */
    fun entityOf(labels: Map<String, String>): String?

    /** One series per host; the entity is the `host_name` label (the OS hostname, e.g. `db0`). */
    data object PerHost : MetricScope {
        override fun entityOf(labels: Map<String, String>): String? = labels[HOST_LABEL]
    }

    /** One series summed across the cluster; the entity is always `cluster`. */
    data object Cluster : MetricScope {
        override fun entityOf(labels: Map<String, String>): String = Constants.LiveMetrics.CLUSTER_ENTITY
    }

    /** `cluster.min` and `cluster.max`, read from the `entity` label the query writes. */
    data object ClusterRange : MetricScope {
        override fun entityOf(labels: Map<String, String>): String? = labels[ENTITY_LABEL]
    }

    private companion object {
        const val HOST_LABEL = "host_name"
        const val ENTITY_LABEL = "entity"
    }
}
