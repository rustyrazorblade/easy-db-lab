package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig

/**
 * What one cycle did, for the terminal summary line.
 *
 * @property dropped samples the cycle had but did not publish, because Redis failed and the stream
 *   reconnected; they are never queued or replayed
 */
data class CycleReport(
    val published: Int,
    val failed: Int,
    val dropped: Int = 0,
) {
    /** The one line printed after the cycle. */
    fun summaryLine(): String =
        "metrics stream: published $published samples, $failed metrics failed" +
            if (dropped > 0) ", $dropped samples dropped (Redis reconnected)" else ""
}

/** How one cycle ended. */
sealed interface CycleOutcome {
    /** The cycle ran to its end, or dropped its rest after a reconnect that succeeded. */
    data class Completed(
        val report: CycleReport,
    ) : CycleOutcome

    /** Redis did not come back after every reconnect attempt. */
    data class RedisLost(
        val url: String,
        val reason: String,
    ) : CycleOutcome

    /** The stream was told to stop; nothing more was published or reported. */
    data object Stopped : CycleOutcome
}

/** A metric could not be queried this cycle; the message is the reason the user sees. */
class MetricUnavailableException(
    reason: String,
) : RuntimeException(reason)

/**
 * One `metrics stream` cycle, run on the command thread: ping Redis, prepare the queries, query
 * each metric, report health changes on the bus, then publish the samples straight to Redis.
 *
 * The stop flag is checked before the ping, before each query, each health record and each
 * publish, and before a Redis failure counts as lost, so nothing is sent after a stop.  A ping or
 * publish failure drops the rest of the cycle and reconnects with [reconnect]; samples that were
 * not published are never queued or replayed.
 *
 * @param reconnect the retry settings of a reconnect: by default the network settings, three
 *   attempts 1 s then 2 s apart
 */
class MetricsCycle(
    private val metrics: List<StreamMetric>,
    private val queries: CycleQueries,
    private val publisher: SamplePublisher,
    private val health: MetricHealth,
    private val eventBus: EventBus,
    private val isStopped: () -> Boolean,
    private val reconnect: RetryConfig = RetryUtil.createNetworkRetryConfig<Unit>(),
) {
    /** Runs the cycle. */
    fun run(): CycleOutcome {
        if (isStopped()) return CycleOutcome.Stopped
        try {
            publisher.ping()
        } catch (e: RedisPublishException) {
            return reconnectAfter(e, CycleReport(published = 0, failed = 0))
        }
        val outcomes = queryAll() ?: return CycleOutcome.Stopped
        if (!recordHealth(outcomes)) return CycleOutcome.Stopped
        val report = CycleReport(published = 0, failed = outcomes.values.count { it.isFailure })
        return publishAll(outcomes.values.flatMap { it.getOrDefault(emptyList()) }, report)
    }

    /** Each metric's outcome, or null when the stream was stopped. */
    private fun queryAll(): Map<StreamMetric, Result<List<Event.Metrics.Sample>>>? {
        val target = queries.prepare()
        val outcomes = linkedMapOf<StreamMetric, Result<List<Event.Metrics.Sample>>>()
        for (metric in metrics) {
            if (isStopped()) return null
            outcomes[metric] =
                when (target) {
                    is CycleQueries.Target.Ready -> target.query(metric)
                    is CycleQueries.Target.Unavailable -> Result.failure(MetricUnavailableException(target.reason))
                }
        }
        return outcomes
    }

    /** Emits each health change; false when the stream was stopped first. */
    private fun recordHealth(outcomes: Map<StreamMetric, Result<List<Event.Metrics.Sample>>>): Boolean {
        for ((metric, outcome) in outcomes) {
            // An interrupted query looks like a failure; a stop must not report it.
            if (isStopped()) return false
            when (val transition = health.record(metric, outcome)) {
                MetricHealth.Transition.None -> Unit
                is MetricHealth.Transition.Failed -> eventBus.emit(Event.Metrics.MetricFailing(metric.metricName, transition.reason))
                MetricHealth.Transition.Recovered -> eventBus.emit(Event.Metrics.MetricRecovered(metric.metricName))
            }
        }
        return true
    }

    private fun publishAll(
        samples: List<Event.Metrics.Sample>,
        report: CycleReport,
    ): CycleOutcome {
        var published = 0
        for (sample in samples) {
            if (isStopped()) return CycleOutcome.Stopped
            try {
                publisher.publish(sample)
                published++
            } catch (e: RedisPublishException) {
                return reconnectAfter(e, report.copy(published = published, dropped = samples.size - published))
            }
        }
        return CycleOutcome.Completed(report.copy(published = published))
    }

    /**
     * Reconnects after [failure]: the cycle ends with [report] when Redis comes back, and as lost
     * when every attempt fails.  A stop during the reconnect ends it as stopped.  A reconnect that
     * succeeds is reported as `Metrics.RedisReconnected`, with the samples the cycle dropped.
     */
    private fun reconnectAfter(
        failure: RedisPublishException,
        report: CycleReport,
    ): CycleOutcome {
        if (isStopped()) return CycleOutcome.Stopped
        log.warn(failure) { "Redis at ${publisher.url} failed (${failure.message}); reconnecting, dropping ${report.dropped} samples" }
        val reconnected =
            runCatching {
                Retry.of(RECONNECT, reconnect).executeRunnable {
                    // A stop ends the attempts; the check below then reports it.
                    if (!isStopped()) publisher.ping()
                }
            }
        return when {
            isStopped() -> CycleOutcome.Stopped
            reconnected.isSuccess -> {
                eventBus.emit(Event.Metrics.RedisReconnected(publisher.url, failure.message.orEmpty(), report.dropped))
                CycleOutcome.Completed(report)
            }
            else -> CycleOutcome.RedisLost(publisher.url, reconnected.exceptionOrNull()?.message ?: failure.message.orEmpty())
        }
    }

    private companion object {
        const val RECONNECT = "metrics-stream-redis-reconnect"
        val log = KotlinLogging.logger {}
    }
}
