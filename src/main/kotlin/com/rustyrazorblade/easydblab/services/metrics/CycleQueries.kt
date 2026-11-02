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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Prepares one `metrics stream` cycle's queries: it reads the cluster state and the route again,
 * so a teardown, a new tunnel or a lost one shows up from the next cycle.
 *
 * @param loadState the workspace's cluster state, or null when the workspace has none
 * @param routes the reuse-only route lookup; it never starts or replaces a tunnel
 * @param httpClientFactory the base HTTP client each cycle's route is applied to
 * @param mimirFor builds the query service on a cycle's [ObservabilityHttp]; tests replace it
 */
class CycleQueries(
    private val loadState: () -> ClusterState?,
    private val routes: ObservabilityRoutes,
    private val httpClientFactory: HttpClientFactory,
    private val mimirFor: (ObservabilityHttp) -> MimirQueryService = ::DefaultMimirQueryService,
) {
    /** How this cycle's metrics can be queried. */
    sealed interface Target {
        /** Queries go to [mimir], scoped to the cluster labelled [cluster]. */
        data class Ready(
            private val mimir: MimirQueryService,
            private val cluster: String,
        ) : Target {
            /** The samples of [metric] now, or why its query failed. */
            fun query(metric: StreamMetric): Result<List<Event.Metrics.Sample>> =
                mimir.query(metric.promql(cluster)).map { metric.samples(it) }
        }

        /** No query can be sent this cycle; every metric fails for [reason]. */
        data class Unavailable(
            val reason: String,
        ) : Target
    }

    /**
     * Reads the state and the route and returns this cycle's target.  A state or route that cannot
     * be read (a file removed or rewritten while the stream runs) fails this cycle's metrics with the
     * reason, so the stream keeps running and recovers when it can read them again.
     */
    fun prepare(): Target =
        runCatching { lookUp() }.getOrElse { e ->
            log.warn(e) { "Could not prepare a metrics stream cycle" }
            Target.Unavailable(e.message ?: e::class.java.simpleName)
        }

    private fun lookUp(): Target {
        val state = loadState()?.takeIf { it.isInfrastructureUp() && it.getControlHost() != null }
        return when {
            state == null -> Target.Unavailable(NOT_RUNNING)
            else ->
                when (val lookup = routes.current(state)) {
                    is RouteLookup.Usable ->
                        Target.Ready(mimirFor(RoutedObservabilityHttp(state, lookup.route, httpClientFactory)), state.clusterLabelName())
                    is RouteLookup.Unavailable -> Target.Unavailable(lookup.reason)
                }
        }
    }

    private companion object {
        const val NOT_RUNNING = "the cluster is not running"
        val log = KotlinLogging.logger {}
    }
}
