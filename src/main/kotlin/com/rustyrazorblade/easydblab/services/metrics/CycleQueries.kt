package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoutes
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import com.rustyrazorblade.easydblab.services.DefaultObservabilityHttp
import com.rustyrazorblade.easydblab.services.MimirQueryService
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.access.RouteServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceAccess

/**
 * Prepares one `metrics stream` cycle's queries: it reads the cluster state and the route again,
 * so a teardown, a new tunnel or a lost one shows up from the next cycle.
 *
 * @param loadState the workspace's cluster state, or null when the workspace has none
 * @param routes the reuse-only recorded-tunnel lookup a Socks cluster uses; it never starts or replaces a tunnel
 * @param httpClientFactory the base HTTP client each cycle's route is applied to
 * @param mimirFor builds the query service on a cycle's [ObservabilityHttp]; tests replace it
 * @param stressJobs finds the stress jobs that finished since the last cycle, over the same route
 * @param forwardedAccess the port-forward seam a PortForward cluster's Services are read through; it
 *   holds its forwards across cycles and reopens one that died
 */
class CycleQueries(
    private val loadState: () -> ClusterState?,
    private val routes: ObservabilityRoutes,
    private val httpClientFactory: HttpClientFactory,
    private val mimirFor: (ObservabilityHttp) -> MimirQueryService = ::DefaultMimirQueryService,
    private val stressJobs: StressJobWatcher = StressJobWatcher { _, _ -> emptyList() },
    private val forwardedAccess: ServiceAccess = ServiceAccess { error("No port-forward access is configured") },
) {
    /** How this cycle's metrics can be queried. */
    sealed interface Target {
        /** Queries go to [mimir], scoped to the cluster labelled [cluster]. */
        data class Ready(
            private val mimir: MimirQueryService,
            private val cluster: String,
            private val stressJobs: (isStopping: () -> Boolean) -> List<Event.Stress.JobCompleted> = { emptyList() },
        ) : Target {
            /** The samples of [metric] now, or why its query failed. */
            fun query(metric: StreamMetric): Result<List<Event.Metrics.Sample>> =
                mimir.query(metric.promql(cluster)).map { metric.samples(it) }

            /**
             * The stress jobs that finished since the last cycle; empty when the list failed.
             * [isStopping] tells the list whether a failure comes from the stream stopping.
             */
            fun completedStressJobs(isStopping: () -> Boolean): List<Event.Stress.JobCompleted> = stressJobs(isStopping)
        }

        /** No query can be sent this cycle; every metric fails for [reason]. */
        data class Unavailable(
            val reason: String,
        ) : Target
    }

    /**
     * Reads the state and returns this cycle's target, reached the way the recorded [Exposure] names:
     * a [Exposure.Socks] cluster through the recorded tunnel (never started here), a [Exposure.Direct]
     * one at the node address with no tunnel, and a [Exposure.PortForward] one through the port-forward
     * seam. Only the clusters easy-db-lab provisions are checked for running infrastructure and a
     * control node; a port-forwarded cluster has neither recorded.
     */
    fun prepare(): Target {
        val state = loadState() ?: return Target.Unavailable(NOT_RUNNING)
        return when (state.exposure) {
            Exposure.Socks ->
                whenProvisioned(state) {
                    when (val lookup = routes.current(state)) {
                        is RouteLookup.Usable -> ready(state, RouteServiceAccess(state, lookup.route), lookup.route)
                        is RouteLookup.Unavailable -> Target.Unavailable(lookup.reason)
                    }
                }
            Exposure.Direct ->
                whenProvisioned(state) { ready(state, RouteServiceAccess(state, ObservabilityRoute.Direct), ObservabilityRoute.Direct) }
            // The Kubernetes API is the kubeconfig's own server, reached with no tunnel.
            Exposure.PortForward -> ready(state, forwardedAccess, ObservabilityRoute.Direct)
        }
    }

    private fun whenProvisioned(
        state: ClusterState,
        target: () -> Target,
    ): Target = if (state.isInfrastructureUp() && state.getControlHost() != null) target() else Target.Unavailable(NOT_RUNNING)

    private fun ready(
        state: ClusterState,
        access: ServiceAccess,
        kubernetesRoute: ObservabilityRoute,
    ) = Target.Ready(
        mimir = mimirFor(DefaultObservabilityHttp(access, state::tenant, httpClientFactory)),
        cluster = state.clusterLabelName(),
        stressJobs = { isStopping -> stressJobs.completed(state, kubernetesRoute, isStopping) },
    )

    private companion object {
        const val NOT_RUNNING = "the cluster is not running"
    }
}
