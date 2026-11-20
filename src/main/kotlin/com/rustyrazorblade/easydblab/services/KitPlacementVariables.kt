package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeSelector
import com.rustyrazorblade.easydblab.configuration.RoleSelectors
import com.rustyrazorblade.easydblab.configuration.ServerType
import kotlinx.serialization.json.Json

/**
 * Where a kit's pods go and how many nodes each role has, as the variables kits read.
 *
 * Templates take `__DB_NODE_SELECTOR__` / `__APP_NODE_SELECTOR__` as JSON maps (`{}` when the role
 * is unconstrained); kit processes take `DB_NODE_LABELS`, `APP_NODE_LABELS` and `CONTROL_NODE_LABELS`
 * as label selectors (empty when unconstrained). `DB_NODE_COUNT` and `APP_NODE_COUNT` are the role
 * pools. It is composed into [TemplateVariables] so that class does not grow a field per variable.
 *
 * @property selectors where each role's pods run
 * @property dbNodeCount the nodes matching the db selector
 * @property appNodeCount the nodes matching the app selector
 */
data class KitPlacementVariables(
    val selectors: RoleSelectors,
    val dbNodeCount: Int,
    val appNodeCount: Int,
) {
    fun toMap(): Map<String, String> =
        mapOf(
            "DB_NODE_SELECTOR" to selectors.db.toJson(),
            "DB_AFFINITY" to cnpgAffinity(selectors.db),
            "APP_NODE_SELECTOR" to selectors.app.toJson(),
            "DB_NODE_LABELS" to selectors.db.toLabelSelector(),
            "APP_NODE_LABELS" to selectors.app.toLabelSelector(),
            "CONTROL_NODE_LABELS" to selectors.control.toLabelSelector(),
            "DB_NODE_COUNT" to dbNodeCount.toString(),
            "APP_NODE_COUNT" to appNodeCount.toString(),
        )

    companion object {
        /**
         * A CNPG `Cluster` `affinity` for [selector]: `{"nodeSelector":{...}}`, or `{}` when it
         * constrains nothing. CNPG restarts an instance whose pod's nodeSelector differs from the
         * Cluster's, and the API server stores an empty map on a pod as no map, so a Cluster that
         * names an empty `nodeSelector` restarts its instance on every reconcile.
         */
        private fun cnpgAffinity(selector: NodeSelector): String =
            Json.encodeToString(
                when (selector.labels.isEmpty()) {
                    true -> emptyMap<String, Map<String, String>>()
                    false -> mapOf("nodeSelector" to selector.labels.toSortedMap() as Map<String, String>)
                },
            )

        /**
         * The placement with counts taken from the hosts `state.json` records. Only for callers
         * that cannot reach the Kubernetes API (help text at startup); every kit install and kit
         * process reads the pools through [RoleNodes].
         */
        fun fromRecordedHosts(state: ClusterState): KitPlacementVariables =
            KitPlacementVariables(
                selectors = RoleSelectors.from(state),
                dbNodeCount = state.hosts[ServerType.Cassandra].orEmpty().size,
                appNodeCount = state.hosts[ServerType.Stress].orEmpty().size,
            )
    }
}
