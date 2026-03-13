package com.rustyrazorblade.easydblab.observability

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

/**
 * Interface for telemetry operations providing tracing and metrics capabilities.
 *
 * Backed by [OtelTelemetryProvider], which delegates to the OTel Java agent's
 * [io.opentelemetry.api.GlobalOpenTelemetry] instance. When the agent is attached,
 * traces and metrics are exported per the OTEL_* environment variable configuration.
 * When running without the agent, all operations are no-ops.
 */
interface TelemetryProvider {
    /**
     * Gets a tracer for creating spans.
     *
     * @param name The name of the tracer (typically the class or component name)
     * @return A Tracer instance
     */
    fun getTracer(name: String): Tracer

    /**
     * Gets a meter for recording metrics.
     *
     * @param name The name of the meter (typically the class or component name)
     * @return A Meter instance
     */
    fun getMeter(name: String): Meter

    /**
     * Executes a block of code within a named span.
     *
     * This is the primary method for instrumenting operations. It handles
     * span lifecycle (start, end), error recording, and attribute attachment.
     *
     * @param name The span name (use constants from TelemetryNames.Spans)
     * @param attributes Optional attributes to attach to the span
     * @param block The code block to execute within the span
     * @return The result of the block execution
     * @throws Exception Any exception thrown by the block is recorded and re-thrown
     */
    fun <T> withSpan(
        name: SpanName,
        attributes: Map<String, String> = emptyMap(),
        block: () -> T,
    ): T

    /**
     * Records a duration metric.
     *
     * @param metric The metric name (use constants from TelemetryNames.Metrics)
     * @param durationMs Duration in milliseconds
     * @param attributes Optional attributes to attach to the metric
     */
    fun recordDuration(
        metric: MetricName,
        durationMs: Long,
        attributes: Map<String, String> = emptyMap(),
    )

    /**
     * Increments a counter metric.
     *
     * @param metric The metric name (use constants from TelemetryNames.Metrics)
     * @param attributes Optional attributes to attach to the metric
     */
    fun incrementCounter(
        metric: MetricName,
        attributes: Map<String, String> = emptyMap(),
    )

    /**
     * Shuts down the telemetry provider, flushing any pending data.
     *
     * Should be called during application shutdown to ensure all telemetry
     * data is exported before the process exits.
     */
    fun shutdown()

    /**
     * Checks if telemetry is enabled.
     *
     * @return true if telemetry is being exported, false for no-op mode
     */
    fun isEnabled(): Boolean
}
