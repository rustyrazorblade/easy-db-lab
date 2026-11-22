package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.Constants

/**
 * The two labels that mark an object as easy-db-lab's and as one cluster's:
 * `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>`. Every object the
 * CLI applies, every cluster-scoped object it creates and the namespace it installs into carry
 * them, so a later sweep can find exactly what one cluster owns. Defined once here and shared with
 * the EKS slice, which reuses it rather than writing the labels again.
 */
object OwnershipLabels {
    /** The labels of an object cluster [clusterId] owns. */
    fun of(clusterId: String): Map<String, String> =
        linkedMapOf(
            Constants.K8s.MANAGED_BY_LABEL to Constants.K8s.MANAGED_BY_VALUE,
            Constants.K8s.CLUSTER_LABEL to clusterId,
        )

    /** The label selector that matches every object cluster [clusterId] owns. */
    fun selector(clusterId: String): String = of(clusterId).entries.joinToString(",") { (key, value) -> "$key=$value" }
}
