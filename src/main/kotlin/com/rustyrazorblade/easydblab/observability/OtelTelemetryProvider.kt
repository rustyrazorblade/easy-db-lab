package com.rustyrazorblade.easydblab.observability

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * OpenTelemetry implementation of TelemetryProvider.
 *
 * Exports traces and metrics via gRPC to the configured OTLP endpoint.
 * This implementation is used when OTEL_EXPORTER_OTLP_ENDPOINT is set.
 *
 * Features:
 * - Batch span processing for efficient trace export
 * - Periodic metric reading with configurable interval
 * - Resource attributes for service identification
 * - Graceful shutdown with flush on exit
 *
 * @param endpoint The OTLP gRPC endpoint (e.g., "http://localhost:4317")
 */
class OtelTelemetryProvider(
    private val endpoint: String,
) : TelemetryProvider {
    private val openTelemetry: OpenTelemetry
    private val sdkTracerProvider: SdkTracerProvider
    private val sdkMeterProvider: SdkMeterProvider

    // Cache for histograms and counters to avoid recreation
    private val histogramCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongHistogram>()
    private val counterCache = ConcurrentHashMap<String, io.opentelemetry.api.metrics.LongCounter>()

    init {
        log.info { "Initializing OpenTelemetry with endpoint: $endpoint" }

        // Build resource with service information
        val resource =
            Resource
                .getDefault()
                .merge(
                    Resource.create(
                        Attributes.of(
                            AttributeKey.stringKey("service.name"),
                            TelemetryNames.SERVICE_NAME,
                            AttributeKey.stringKey("service.version"),
                            getVersion(),
                            AttributeKey.stringKey("host.name"),
                            getHostName(),
                        ),
                    ),
                )

        // Configure span exporter
        val spanExporter =
            OtlpGrpcSpanExporter
                .builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(EXPORT_TIMEOUT_SECONDS))
                .build()

        // Configure tracer provider with batch processor
        sdkTracerProvider =
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .addSpanProcessor(
                    BatchSpanProcessor
                        .builder(spanExporter)
                        .setScheduleDelay(Duration.ofMillis(BATCH_DELAY_MS))
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(MAX_BATCH_SIZE)
                        .build(),
                ).build()

        // Configure metric exporter
        val metricExporter =
            OtlpGrpcMetricExporter
                .builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(EXPORT_TIMEOUT_SECONDS))
                .build()

        // Configure meter provider with periodic reader
        sdkMeterProvider =
            SdkMeterProvider
                .builder()
                .setResource(resource)
                .registerMetricReader(
                    PeriodicMetricReader
                        .builder(metricExporter)
                        .setInterval(Duration.ofSeconds(METRIC_EXPORT_INTERVAL_SECONDS))
                        .build(),
                ).build()

        // Build the OpenTelemetry instance
        openTelemetry =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(sdkTracerProvider)
                .setMeterProvider(sdkMeterProvider)
                .build()

        log.info { "OpenTelemetry initialized successfully" }
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
            // Flush and shutdown tracer provider
            sdkTracerProvider.forceFlush().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sdkTracerProvider.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Flush and shutdown meter provider
            sdkMeterProvider.forceFlush().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sdkMeterProvider.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

    private fun getVersion(): String = System.getProperty("easydblab.version", "unknown")

    private fun getHostName(): String =
        try {
            java.net.InetAddress
                .getLocalHost()
                .hostName
        } catch (e: Exception) {
            "unknown"
        }

    companion object {
        private const val EXPORT_TIMEOUT_SECONDS = 10L
        private const val BATCH_DELAY_MS = 1000L
        private const val MAX_QUEUE_SIZE = 2048
        private const val MAX_BATCH_SIZE = 512
        private const val METRIC_EXPORT_INTERVAL_SECONDS = 30L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
