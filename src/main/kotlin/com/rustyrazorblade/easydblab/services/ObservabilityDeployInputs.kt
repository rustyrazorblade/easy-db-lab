package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.region

/**
 * What one observability stack deploy reads about the cluster, read once at its start so every stage
 * sees the same state, contract and region.
 *
 * @property contractSource the workspace's cluster contract, so the capabilities that gate stages and
 *   the hosting the backends and Grafana are placed with come from one reading. A test binds a fixed
 *   contract to run a non-AWS environment.
 */
class ObservabilityDeployInputs(
    private val clusterStateManager: ClusterStateManager,
    private val contractSource: ClusterContractSource,
    private val profileRegion: ProfileRegion,
) {
    /** One reading: the cluster state, its contract, and its region (the profile's when it records none). */
    data class Reading(
        val state: ClusterState,
        val contract: ClusterContract,
        val region: String,
    )

    fun read(): Reading {
        val state = clusterStateManager.load()
        return Reading(state, contractSource.current(), state.region(profileRegion))
    }
}

/**
 * Grafana's part of a deploy: its datasources for every tenant in the shared store, the dashboards
 * with this cluster's install-time pass, and its K8s resources ([GrafanaDeployService]).
 */
class GrafanaStage(
    private val grafanaDeployService: GrafanaDeployService,
    private val installContextFactory: DashboardInstallContextFactory,
) {
    /** Deploys Grafana for [clusterState] placed by [hosting]; fails the deploy if it cannot. */
    fun deploy(
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
    ) {
        grafanaDeployService
            .deploy(
                // Null on a cluster the operator brought; only the dashboard tree copy needs it.
                clusterState.getControlHost(),
                installContextFactory.forCluster(clusterState),
                ObservabilityStore.from(clusterState).bucket,
                hosting,
            ).getOrElse { exception ->
                throw IllegalStateException("Failed to upload dashboards: ${exception.message}", exception)
            }
    }
}
