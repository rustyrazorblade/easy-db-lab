package com.rustyrazorblade.easydblab.observability

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * OpenTelemetry implementation of TelemetryProvider using auto-configuration.
 *
 * Uses AutoConfiguredOpenTelemetrySdk to automatically read standard OTEL environment variables:
 * - OTEL_EXPORTER_OTLP_ENDPOINT: The OTLP endpoint for exporting telemetry
 * - OTEL_RESOURCE_ATTRIBUTES: Additional resource attributes (key=value,key2=value2)
 * - OTEL_SERVICE_NAME: Service name (defaults to "easy-db-lab" if not set)
 *
 * Features:
 * - Automatic configuration from environment variables
 * - Log export via Logback appender bridge
 * - Resource attributes for service identification
 * - Graceful shutdown with flush on exit
 */
class OtelTelemetryProvider : TelemetryProvider {
    private val openTelemetry: OpenTelemetry
    private val sdk: OpenTelemetrySdk

    // Cache for histograms and counters to avoid recreation
    private val histogramCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongHistogram>()
    private val counterCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongCounter>()

    init {
        log.info { "Initializing OpenTelemetry with auto-configuration" }

        val autoConfiguredSdk =
            AutoConfiguredOpenTelemetrySdk
                .builder()
                .addResourceCustomizer { envResource, _ ->
                    OtelResourceBuilder.buildResource(envResource)
                }.disableShutdownHook() // We handle shutdown manually
                .build()

        sdk = autoConfiguredSdk.openTelemetrySdk
        openTelemetry = sdk

        // Install the Logback appender bridge to export logs via OTel
        OpenTelemetryAppender.install(openTelemetry)

        // Log effective configuration for debugging
        logEffectiveConfiguration()
    }

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
        log.info { "Shutting down OpenTelemetry..." }
        try {
            sdk.sdkTracerProvider.forceFlush().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sdk.sdkMeterProvider.forceFlush().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sdk.sdkLoggerProvider.forceFlush().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sdk.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            log.info { "OpenTelemetry shutdown complete" }
        } catch (e: Exception) {
            log.warn(e) { "Error during OpenTelemetry shutdown" }
        }
    }

    override fun isEnabled(): Boolean = true

    /**
     * Gets the current OpenTelemetry instance for use with auto-instrumentation libraries.
     *
     * This is needed for configuring AWS SDK and OkHttp auto-instrumentation.
     */
    fun getOpenTelemetry(): OpenTelemetry = openTelemetry

    private fun buildAttributes(attributes: Map<String, String>): Attributes {
        val builder = Attributes.builder()
        attributes.forEach { (key, value) ->
            builder.put(AttributeKey.stringKey(key), value)
        }
        return builder.build()
    }

    private fun logEffectiveConfiguration() {
        val endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") ?: "default"
        val resourceAttrs = System.getenv("OTEL_RESOURCE_ATTRIBUTES")

        log.info { "OpenTelemetry initialized successfully" }
        log.info { "  Endpoint: $endpoint" }
        log.info { "  Service name: ${TelemetryNames.SERVICE_NAME}" }
        if (!resourceAttrs.isNullOrBlank()) {
            log.info { "  Resource attributes from env: $resourceAttrs" }
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
