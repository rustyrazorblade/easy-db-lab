package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet

/**
 * Finds the tenants in the shared store, so Grafana gets a datasource for each.
 *
 * Mimir makes one directory per tenant under `mimir/` in the account bucket. The listing is one
 * `ListObjectsV2` with `Delimiter=/`; a directory whose name breaks the tenant name rule, such as
 * Mimir's own `__mimir_cluster/`, is not a tenant.
 */
class TenantDirectory(
    private val objectStore: ObjectStore,
) {
    companion object {
        private val TENANT = Regex(Constants.Observability.TENANT_PATTERN)

        /** The tenants among the directory [names], with [home] added. */
        fun tenantsFrom(
            names: List<String>,
            home: String,
        ): TenantSet = TenantSet.of(home, names.filter { TENANT.matches(it) && it != Constants.Observability.RESERVED_TENANT })
    }

    /** Every tenant with a directory under `mimir/` in [bucket], and [home]. */
    fun list(
        bucket: String,
        home: String,
    ): TenantSet {
        val root = Constants.Observability.METRICS_ROOT
        val names =
            objectStore
                .listFiles(ClusterS3Path.root(bucket).resolve(root), recursive = false)
                .map {
                    it.path
                        .getKey()
                        .removePrefix("$root/")
                        .trimEnd('/')
                }
        return tenantsFrom(names, home)
    }
}
