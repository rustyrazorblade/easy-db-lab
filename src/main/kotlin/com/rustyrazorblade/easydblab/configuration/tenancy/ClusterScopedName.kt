package com.rustyrazorblade.easydblab.configuration.tenancy

/**
 * The name of a cluster-scoped object (a ClusterRole, a ClusterRoleBinding): its base name with the
 * full cluster id appended, so two easy-db-lab clusters sharing one Kubernetes cluster never take
 * each other's objects. Applied in every environment, with no branch.
 */
object ClusterScopedName {
    /** `<base>-<clusterId>`. */
    fun of(
        base: String,
        clusterId: String,
    ): String = "$base-$clusterId"
}
