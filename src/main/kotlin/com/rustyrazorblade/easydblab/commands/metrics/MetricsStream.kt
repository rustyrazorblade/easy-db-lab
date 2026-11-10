package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.RedisConfiguration
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.metrics.MetricSelection
import com.rustyrazorblade.easydblab.services.metrics.MetricsStreamService
import com.rustyrazorblade.easydblab.services.metrics.StreamEnd
import com.rustyrazorblade.easydblab.services.metrics.StreamMetric
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Publishes chosen metrics to the Redis channel `EASY_DB_LAB_REDIS_URL` names, every 5 seconds, until
 * it is stopped.  Each value is one `Metrics.Sample` on Redis; the terminal shows one summary line
 * per cycle.
 *
 * The command only checks its preconditions, in order: the `--metrics` names, the Redis URL, a running
 * cluster, and a local telemetry stack.  Each failure is a typed event and a non-zero exit before the
 * first cycle.  [MetricsStreamService] does the streaming.  `@RequiresProxy` starts or reuses the tunnel
 * before the first cycle; after that the stream only reuses the recorded one.  The first Ctrl-C or
 * SIGTERM returns normally, so the command exits 0.
 */
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "stream",
    description = ["Publish chosen metrics to Redis every 5 seconds until stopped (see 'metrics list' for the names)"],
)
class MetricsStream : PicoBaseCommand() {
    private val redisConfiguration: RedisConfiguration by inject()

    // Resolved only after the Redis check: the service needs the shared Redis publisher.
    private val streamService: MetricsStreamService by inject()

    @Option(
        names = ["--metrics"],
        description = ["Comma-separated metric names to stream (default: the 11 default names; see 'metrics list')"],
    )
    var metrics: String? = null

    override fun execute() {
        val selected = selectedMetrics()
        requireRedis()
        requireRunningCluster()
        requireLocalTelemetryStack(COMMAND)
        when (val end = streamService.run(selected) { println(it.summaryLine()) }) {
            StreamEnd.Stopped -> Unit
            is StreamEnd.RedisLost -> {
                eventBus.emit(Event.Metrics.RedisLost(end.url, end.reason))
                throw CommandFailedException("lost Redis at ${end.url}")
            }
        }
    }

    private fun selectedMetrics(): List<StreamMetric> =
        when (val selection = MetricSelection.parse(metrics)) {
            is MetricSelection.Selected -> selection.metrics
            is MetricSelection.Rejected -> {
                eventBus.emit(Event.Metrics.InvalidNames(selection.entries))
                throw CommandFailedException("invalid --metrics entries")
            }
        }

    private fun requireRedis() {
        when (redisConfiguration) {
            is RedisConfiguration.Configured -> Unit
            RedisConfiguration.NotConfigured -> {
                eventBus.emit(Event.Metrics.RedisNotConfigured(Constants.EventBus.REDIS_URL_ENV_VAR))
                throw CommandFailedException("${Constants.EventBus.REDIS_URL_ENV_VAR} is not set")
            }
        }
    }

    private fun requireRunningCluster() {
        val running =
            clusterStateManager
                .takeIf { it.exists() }
                ?.load()
                ?.takeIf { it.isInfrastructureUp() && it.getControlHost() != null }
        if (running == null) {
            val workspace = context.workingDirectory.absolutePath
            eventBus.emit(Event.Metrics.NoRunningCluster(workspace))
            throw CommandFailedException("no running cluster in $workspace")
        }
    }

    private companion object {
        const val COMMAND = "metrics stream"
    }
}
