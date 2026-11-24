package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet

/**
 * Finds the tenants in the shared store, so Grafana gets a datasource for each.
 *
 * Mimir makes one directory per tenant under `mimir/` in the account bucket. The listing is one
 * `ListObjectsV2` with `Delimiter=/`; a directory whose name breaks the tenant name rule, such as
 * Mimir's own `__mimir_cluster/`, is not a tenant.
 *
 * The object store is built on the first listing, not with this object: the S3 store needs the
 * profile, and the observability services that hold one are also built in workspaces with no AWS
 * account, where nothing lists the store.
 */
class TenantDirectory(
    private val objectStore: Lazy<ObjectStore>,
) {
    constructor(objectStore: ObjectStore) : this(lazyOf(objectStore))

    companion object {
        private val TENANT = Regex(Constants.Observability.TENANT_PATTERN)

        /** The tenants among the directory [names], with [home] added. */
        fun tenantsFrom(
            names: List<String>,
            home: String,
        ): TenantSet = TenantSet.of(home, names.filter { TENANT.matches(it) && it != Constants.Observability.RESERVED_TENANT })
    }

    /** Every tenant with a directory under Mimir's root in [store] (its bucket and store prefix), and its home tenant. */
    fun list(store: ObservabilityStore): TenantSet {
        val root = store.metricsRoot()
        val rootKey = root.getKey()
        val names =
            objectStore.value
                .listFiles(root, recursive = false)
                .map {
                    it.path
                        .getKey()
                        .removePrefix("$rootKey/")
                        .trimEnd('/')
                }
        return tenantsFrom(names, store.tenant)
    }
}
