package com.rustyrazorblade.easydblab.configuration.tenancy

/**
 * The name of a cluster-scoped object (a ClusterRole, a ClusterRoleBinding): its base name with the
 * full cluster id appended, so two easy-db-lab clusters sharing one Kubernetes cluster never take
 * each other's objects. Applied in every environment, with no branch.
 *
 * The result is a DNS subdomain name, which Kubernetes limits to 253 characters, as it does
 * ClusterRole and ClusterRoleBinding names. With a 36-character UUID cluster id it is far longer
 * than a 63-character DNS label allows once the base is long, so it must never be used for a label
 * value, a Service, a namespace or any other DNS-label-limited name; today every caller names a
 * ClusterRole or a ClusterRoleBinding.
 */
object ClusterScopedName {
    /** `<base>-<clusterId>`. */
    fun of(
        base: String,
        clusterId: String,
    ): String = "$base-$clusterId"
}
