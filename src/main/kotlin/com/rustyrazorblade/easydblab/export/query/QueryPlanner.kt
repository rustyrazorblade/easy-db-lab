package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.export.ExportSelector
import java.time.Clock
import java.time.Instant

/**
 * What a query export reads.
 *
 * @property home the tenant of the opened export's stable datasources: the experiment's, or the first
 *   cluster's (owner decision D17).
 * @property clusters each cluster and its tenant, in the order given or found.
 * @property ranges when each cluster ran.
 * @property covered the experiments the clusters belong to, whose reports the zip holds.
 * @property experiment the experiment of an experiment export, which the dashboards open on.
 * @property tenantSources where a cluster export found each cluster's tenant; empty for an experiment
 *   export, whose tenant is the experiment's.
 */
data class QueryPlan(
    val home: String,
    val clusters: Map<String, String>,
    val ranges: Map<String, TimeRange>,
    val covered: List<ExperimentKey>,
    val experiment: ExperimentKey?,
    val tenantSources: Map<String, ClusterTenantSource> = emptyMap(),
) {
    /** Every tenant of the clusters, the home tenant first: the zip's tenant set is their union. */
    val tenants: List<String> get() = (listOf(home) + clusters.values).distinct()

    /** The clusters of [tenant]. */
    fun clustersOf(tenant: String): List<String> = clusters.filterValues { it == tenant }.keys.toList()
}

/**
 * Turns a `--cluster` or `--experiment` selection into a [QueryPlan], reading only the reader's Mimir:
 * the clusters and their tenants ([ClusterTenantResolver], [ExperimentDiscovery]), the experiments
 * they cover, and when each ran ([ClusterTimeRanges]).
 *
 * @property mimir the reader's Mimir.
 * @property oldestBlock the start of a tenant's oldest metric block, where the range search begins.
 * @property clock now.
 */
class QueryPlanner(
    private val mimir: MimirApi,
    private val oldestBlock: (String) -> Instant?,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * The plan for [selector], with [storeTenants] the tenant directories of the store and
     * [workspace] the workspace the command runs in.
     *
     * @throws QueryExportRefused when a cluster or the experiment cannot be found.
     */
    fun plan(
        selector: ExportSelector,
        storeTenants: List<String>,
        workspace: WorkspaceCluster?,
    ): QueryPlan {
        val discovery = ExperimentDiscovery(mimir)
        val clusters: Map<String, String>
        val sources: Map<String, ClusterTenantSource>
        val experiment: ExperimentKey?
        when (selector) {
            is ExportSelector.Clusters -> {
                val resolved = ClusterTenantResolver(mimir, storeTenants, workspace).resolveWithSources(selector.ids)
                clusters = resolved.mapValues { it.value.tenant }
                sources = resolved.mapValues { it.value.source }
                experiment = null
            }
            is ExportSelector.Experiment -> {
                clusters = discovery.clustersOf(selector.key).associateWith { selector.key.tenant }
                sources = emptyMap()
                experiment = selector.key
            }
            is ExportSelector.Tenant -> throw IllegalArgumentException("A tenant export copies the store; it is not a query export")
        }
        val home = experiment?.tenant ?: clusters.values.first()
        val byTenant = clusters.entries.groupBy({ it.value }, { it.key })
        val ranges =
            byTenant.entries.fold(emptyMap<String, TimeRange>()) { found, (tenant, ofTenant) ->
                found + ClusterTimeRanges(mimir, clock).of(tenant, ofTenant, oldestBlock(tenant) ?: Instant.EPOCH)
            }
        val covered = experiment?.let(::listOf) ?: byTenant.flatMap { (tenant, ofTenant) -> discovery.coveredBy(tenant, ofTenant) }
        return QueryPlan(home, clusters, ranges, covered.distinct(), experiment, sources)
    }
}
