package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoutes
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import com.rustyrazorblade.easydblab.services.MimirQueryService
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.RoutedObservabilityHttp

/**
 * Prepares one `metrics stream` cycle's queries: it reads the cluster state and the route again,
 * so a teardown, a new tunnel or a lost one shows up from the next cycle.
 *
 * @param loadState the workspace's cluster state, or null when the workspace has none
 * @param routes the reuse-only route lookup; it never starts or replaces a tunnel
 * @param httpClientFactory the base HTTP client each cycle's route is applied to
 * @param mimirFor builds the query service on a cycle's [ObservabilityHttp]; tests replace it
 * @param stressJobs finds the stress jobs that finished since the last cycle, over the same route
 */
class CycleQueries(
    private val loadState: () -> ClusterState?,
    private val routes: ObservabilityRoutes,
    private val httpClientFactory: HttpClientFactory,
    private val mimirFor: (ObservabilityHttp) -> MimirQueryService = ::DefaultMimirQueryService,
    private val stressJobs: StressJobWatcher = StressJobWatcher { _, _ -> emptyList() },
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

    /** Reads the state and the route and returns this cycle's target. */
    fun prepare(): Target {
        val state = loadState()?.takeIf { it.isInfrastructureUp() && it.getControlHost() != null }
        return when {
            state == null -> Target.Unavailable(NOT_RUNNING)
            else ->
                when (val lookup = routes.current(state)) {
                    is RouteLookup.Usable ->
                        Target.Ready(
                            mimir = mimirFor(RoutedObservabilityHttp(state, lookup.route, httpClientFactory)),
                            cluster = state.clusterLabelName(),
                            stressJobs = { isStopping -> stressJobs.completed(state, lookup.route, isStopping) },
                        )
                    is RouteLookup.Unavailable -> Target.Unavailable(lookup.reason)
                }
        }
    }

    private companion object {
        const val NOT_RUNNING = "the cluster is not running"
    }
}
