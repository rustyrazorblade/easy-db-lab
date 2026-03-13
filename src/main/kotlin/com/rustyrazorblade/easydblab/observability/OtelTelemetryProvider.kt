package com.rustyrazorblade.easydblab.observability

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * OpenTelemetry implementation of TelemetryProvider backed by the OTel Java agent.
 *
 * Delegates to [GlobalOpenTelemetry], which is initialized by the OTel Java agent
 * when the JVM starts with `-javaagent:/app/otel/opentelemetry-javaagent.jar`.
 *
 * The agent handles:
 * - SDK initialization from standard OTEL_* environment variables
 * - OTLP export configuration
 * - Bytecode instrumentation of AWS SDK, OkHttp, and Logback
 * - Graceful shutdown and flush on JVM exit
 *
 * When the agent is not attached (e.g., running the CLI locally without configuration),
 * [GlobalOpenTelemetry] returns a no-op instance and all operations are zero-cost.
 */
class OtelTelemetryProvider : TelemetryProvider {
    private val openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get()

    // Cache for histograms and counters to avoid recreation
    private val histogramCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongHistogram>()
    private val counterCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongCounter>()

    override fun getTracer(name: String): Tracer = openTelemetry.getTracer(name)

    override fun getMeter(name: String): Meter = openTelemetry.getMeter(name)

    override fun <T> withSpan(
        name: SpanName,
        attributes: Map<String, String>,
        block: () -> T,
    ): T {
        val tracer = getTracer(TelemetryNames.SERVICE_NAME)
        val spanBuilder = tracer.spanBuilder(name)

        // Attach to current context if one exists
        val parentContext = Context.current()
        spanBuilder.setParent(parentContext)

        // Add attributes
        attributes.forEach { (key, value) ->
            spanBuilder.setAttribute(AttributeKey.stringKey(key), value)
        }

        val span = spanBuilder.startSpan()
        val scope = span.makeCurrent()

        return try {
            val result = block()
            span.setStatus(StatusCode.OK)
            result
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            span.recordException(e)
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }

    override fun recordDuration(
        metric: MetricName,
        durationMs: Long,
        attributes: Map<String, String>,
    ) {
        val histogram =
            histogramCache.getOrPut(metric) {
                getMeter(TelemetryNames.SERVICE_NAME)
                    .histogramBuilder(metric)
                    .setUnit("ms")
                    .setDescription("Duration in milliseconds")
                    .ofLongs()
                    .build()
            }

        val otelAttributes = buildAttributes(attributes)
        histogram.record(durationMs, otelAttributes)
    }

    override fun incrementCounter(
        metric: MetricName,
        attributes: Map<String, String>,
    ) {
        val counter =
            counterCache.getOrPut(metric) {
                getMeter(TelemetryNames.SERVICE_NAME)
                    .counterBuilder(metric)
                    .setDescription("Count of operations")
                    .build()
            }

        val otelAttributes = buildAttributes(attributes)
        counter.add(1, otelAttributes)
    }

    override fun shutdown() {
        // Shutdown and flush are handled by the OTel Java agent's shutdown hook
        log.debug { "OpenTelemetry shutdown delegated to the agent" }
    }

    override fun isEnabled(): Boolean = true

    private fun buildAttributes(attributes: Map<String, String>): Attributes {
        val builder = Attributes.builder()
        attributes.forEach { (key, value) ->
            builder.put(AttributeKey.stringKey(key), value)
        }
        return builder.build()
    }
}
