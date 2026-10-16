package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

/**
 * The three labels every signal a cluster produces carries: `cluster`, `experiment` and
 * `edl_tenant`.
 *
 * Producers whose labels are fixed when they are built (the Pyroscope Java agents, the annotation
 * mirror) take them from here, so none of them can set `cluster` and forget the other two. Producers
 * that read the `cluster-config` ConfigMap at runtime set the same three from `CLUSTER_NAME`,
 * `EXPERIMENT` and `TENANT`.
 *
 * @property cluster the cluster's `<name>-<id>`.
 * @property experiment the experiment the cluster belongs to.
 * @property tenant the cluster's observability tenant.
 */
data class ClusterLabels(
    val cluster: String,
    val experiment: String,
    val tenant: String,
) {
    /** The labels by name. */
    fun asMap(): Map<String, String> =
        mapOf(
            "cluster" to cluster,
            Constants.Observability.EXPERIMENT_LABEL to experiment,
            Constants.Observability.TENANT_LABEL to tenant,
        )

    /** The labels as Pyroscope Java agent pairs, `k=v,k=v`; the agent drops labels written `k:v`. */
    fun pyroscopePairs(): String = asMap().entries.joinToString(",") { (key, value) -> "$key=$value" }

    /** Builds the labels from a cluster state. */
    companion object {
        /** The labels of the cluster [state] describes. */
        fun of(state: ClusterState): ClusterLabels = ClusterLabels(state.clusterLabelName(), state.experiment(), state.tenant())
    }
}
