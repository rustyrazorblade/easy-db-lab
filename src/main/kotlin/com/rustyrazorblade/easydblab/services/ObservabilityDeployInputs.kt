package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.ServiceDns
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.region
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.profiling.pyroscopeBrowserUrl

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
    /** One reading: the cluster state, its contract, its region (the profile's when it records none) and the scope (namespace and cluster id) every resource is installed with. */
    data class Reading(
        val state: ClusterState,
        val contract: ClusterContract,
        val region: String,
        val scope: InstallScope,
    )

    fun read(): Reading {
        val state = clusterStateManager.load()
        val contract = contractSource.current()
        return Reading(state, contract, state.region(profileRegion), InstallScope.of(state, contract))
    }
}

/**
 * Grafana's part of a deploy: its datasources for every tenant in the shared store, the dashboards
 * with this cluster's install-time pass, and its K8s resources ([GrafanaDeployService]).
 *
 * Split in two so the stack deploy can [prepare] before it writes anything, and a dashboard that
 * cannot be delivered fails the deploy with the cluster untouched; [apply] runs in Grafana's place
 * in the stack order.
 */
class GrafanaStage(
    private val grafanaDeployService: GrafanaDeployService,
    private val installContextFactory: DashboardInstallContextFactory,
) {
    /** Renders and checks Grafana for [clusterState] placed by [hosting]; writes nothing, fails the deploy if it cannot. */
    fun prepare(
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
        scope: InstallScope,
    ): GrafanaRelease =
        grafanaDeployService
            .prepare(
                installContextFactory.forCluster(clusterState),
                ObservabilityStore.from(clusterState).bucket,
                hosting,
                // The control host is null on a cluster the operator brought, which publishes no NodePorts.
                pyroscopeBrowserUrl(hosting.publishing, clusterState.getControlHost(), ServiceDns(scope.namespace)),
                scope,
            ).getOrElse { exception ->
                throw IllegalStateException("Failed to deploy Grafana: ${exception.message}", exception)
            }

    /** Applies [release]; fails the deploy if it cannot. */
    fun apply(release: GrafanaRelease) {
        grafanaDeployService.apply(release).getOrElse { exception ->
            throw IllegalStateException("Failed to deploy Grafana: ${exception.message}", exception)
        }
    }
}
