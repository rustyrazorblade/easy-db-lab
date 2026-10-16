package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants

/**
 * The cluster workspace the command runs in.
 *
 * @property cluster its `<name>-<id>`.
 * @property tenant its tenant.
 */
data class WorkspaceCluster(
    val cluster: String,
    val tenant: String,
)

/** A query export cannot go on with what it was asked for; the message says why. */
sealed class QueryExportRefused(
    message: String,
) : IllegalStateException(message)

/**
 * No tenant holds [cluster].
 *
 * @property searched the tenants that were searched.
 */
class ClusterNotFoundException(
    val cluster: String,
    val searched: List<String>,
) : QueryExportRefused(
        if (searched.isEmpty()) {
            "Cluster $cluster was not found: the store holds no tenant"
        } else {
            "Cluster $cluster was not found in tenant${if (searched.size > 1) "s" else ""} ${searched.joinToString(", ")}"
        },
    )

/** [cluster] has data in each of [tenants], so its tenant cannot be told. */
class ClusterInSeveralTenantsException(
    val cluster: String,
    val tenants: List<String>,
) : QueryExportRefused("Cluster $cluster has data in several tenants: ${tenants.joinToString(", ")}")

/** A federated answer for [cluster] had a series that names no tenant. */
class ClusterTenantUnknownException(
    val cluster: String,
    val tenantSet: String,
) : QueryExportRefused("Mimir named no tenant for a series of cluster $cluster in the federated query of $tenantSet")

/**
 * Finds the tenant of each cluster an `export --cluster` names (design section 7, spike S7):
 *
 * 1. inside the cluster's own workspace, the state's tenant, with no query;
 * 2. with one tenant in the store, that tenant, after one series query in it that must find the
 *    cluster (a single-tenant answer carries no `__tenant_id__`, so none is looked for);
 * 3. with several, one series query over every tenant joined by `|`, whose answer gives each series
 *    its tenant in `__tenant_id__`, labelled and pre-label series alike.
 *
 * It never asks `/api/v1/label/__tenant_id__/values`: the federation layer answers that from the
 * header's tenant list and ignores the match. A listed tenant with no data does not fail the query.
 * Clusters of different tenants may be exported together.
 *
 * @property mimir the reader's Mimir.
 * @property storeTenants every tenant directory in the store ([com.rustyrazorblade.easydblab.services.TenantDirectory]).
 * @property workspace the workspace the command runs in, if any.
 */
class ClusterTenantResolver(
    private val mimir: MimirApi,
    private val storeTenants: List<String>,
    private val workspace: WorkspaceCluster?,
) {
    /** The tenant of each of [clusters], in the order given. */
    fun resolve(clusters: List<String>): Map<String, String> = clusters.associateWith(::tenantOf)

    private fun tenantOf(cluster: String): String {
        workspace?.takeIf { it.cluster == cluster }?.let { return it.tenant }
        val series = if (storeTenants.isEmpty()) emptyList() else mimir.series(storeTenants, "up{cluster=\"$cluster\"}")
        if (series.isEmpty()) throw ClusterNotFoundException(cluster, storeTenants)
        // A single-tenant answer names no tenant: it is the one listed.
        return storeTenants.singleOrNull() ?: federatedTenant(cluster, series)
    }

    /** The one tenant a federated answer's [series] of [cluster] name in `__tenant_id__`. */
    private fun federatedTenant(
        cluster: String,
        series: List<Map<String, String>>,
    ): String {
        val named = series.map { it[Constants.Export.Query.TENANT_ID_LABEL] }
        if (named.any { it == null }) {
            throw ClusterTenantUnknownException(cluster, storeTenants.joinToString(Constants.Export.Query.TENANT_SEPARATOR))
        }
        val tenants = named.filterNotNull().distinct().sorted()
        return tenants.singleOrNull() ?: throw ClusterInSeveralTenantsException(cluster, tenants)
    }
}
