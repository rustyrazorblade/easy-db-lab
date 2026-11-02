package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.resilience4j.retry.RetryConfig

/** How a `metrics stream` run ended. */
sealed interface StreamEnd {
    /** A stop signal ended it; the command exits 0. */
    data object Stopped : StreamEnd

    /** Redis at [url] did not come back; the command exits non-zero. */
    data class RedisLost(
        val url: String,
        val reason: String,
    ) : StreamEnd
}

/**
 * Runs `metrics stream`: it installs the stop signals, then runs one [MetricsCycle] per tick on the
 * calling thread until a stop or a lost Redis.  It composes the pieces and holds no logic of its own
 * beyond mapping a cycle's end to the run's end.
 */
class MetricsStreamService(
    private val queries: CycleQueries,
    private val publisher: SamplePublisher,
    private val eventBus: EventBus,
    private val stopSignals: StopSignals,
    private val tickLoop: TickLoop = TickLoop(),
    val reconnect: RetryConfig = RetryUtil.createNetworkRetryConfig<Unit>(),
) {
    /** Streams [metrics] and calls [onCycle] with each completed cycle's report. */
    fun run(
        metrics: List<StreamMetric>,
        onCycle: (CycleReport) -> Unit,
    ): StreamEnd {
        stopSignals.install(Thread.currentThread())
        try {
            val cycle =
                MetricsCycle(metrics, queries, publisher, MetricHealth(), eventBus, { stopSignals.isStopped }, reconnect)
            var end: StreamEnd = StreamEnd.Stopped
            tickLoop.run(isStopped = { stopSignals.isStopped }) {
                when (val outcome = cycle.run()) {
                    is CycleOutcome.Completed -> {
                        onCycle(outcome.report)
                        true
                    }
                    is CycleOutcome.RedisLost -> {
                        end = StreamEnd.RedisLost(outcome.url, outcome.reason)
                        false
                    }
                    CycleOutcome.Stopped -> false
                }
            }
            return end
        } finally {
            stopSignals.close()
        }
    }
}
