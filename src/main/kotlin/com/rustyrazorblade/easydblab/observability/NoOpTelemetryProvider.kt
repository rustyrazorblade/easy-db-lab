package com.rustyrazorblade.easydblab.observability

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

/**
 * No-operation implementation of TelemetryProvider.
 *
 * Used when OpenTelemetry is not configured (OTEL_EXPORTER_OTLP_ENDPOINT not set).
 * All operations are no-ops with minimal overhead:
 * - withSpan() executes the block directly without any tracing
 * - Metric recording methods do nothing
 * - Returns no-op Tracer and Meter instances from OpenTelemetry API
 */
class NoOpTelemetryProvider : TelemetryProvider {
    private val noOpTracer: Tracer =
        io.opentelemetry.api.trace.TracerProvider
            .noop()
            .get("noop")
    private val noOpMeter: Meter =
        io.opentelemetry.api.metrics.MeterProvider
            .noop()
            .get("noop")

    override fun getTracer(name: String): Tracer = noOpTracer

    override fun getMeter(name: String): Meter = noOpMeter

    override fun <T> withSpan(
        name: SpanName,
        attributes: Map<String, String>,
        block: () -> T,
    ): T = block()

    override fun recordDuration(
        metric: MetricName,
        durationMs: Long,
        attributes: Map<String, String>,
    ) {
        // No-op
    }

    override fun incrementCounter(
        metric: MetricName,
        attributes: Map<String, String>,
    ) {
        // No-op
    }

    override fun shutdown() {
        // No-op
    }

    override fun isEnabled(): Boolean = false
}
