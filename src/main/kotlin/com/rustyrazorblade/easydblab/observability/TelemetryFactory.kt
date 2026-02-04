package com.rustyrazorblade.easydblab.observability

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Factory for creating TelemetryProvider instances based on environment configuration.
 *
 * Behavior:
 * - If OTEL_EXPORTER_OTLP_ENDPOINT is set: creates OtelTelemetryProvider (traces & metrics exported)
 * - If OTEL_EXPORTER_OTLP_ENDPOINT is not set: creates NoOpTelemetryProvider (zero overhead)
 *
 * Usage:
 * ```kotlin
 * val telemetry = TelemetryFactory.create()
 * telemetry.withSpan("operation.name") {
 *     // instrumented code
 * }
 * ```
 */
object TelemetryFactory {
    /**
     * Environment variable name for configuring the OTLP endpoint.
     */
    const val OTEL_ENDPOINT_ENV_VAR = "OTEL_EXPORTER_OTLP_ENDPOINT"

    /**
     * Creates a TelemetryProvider based on environment configuration.
     *
     * @return OtelTelemetryProvider if OTEL_EXPORTER_OTLP_ENDPOINT is set,
     *         NoOpTelemetryProvider otherwise
     */
    @Suppress("TooGenericExceptionCaught")
    fun create(): TelemetryProvider {
        val endpoint = System.getenv(OTEL_ENDPOINT_ENV_VAR)

        return if (endpoint.isNullOrBlank()) {
            log.debug { "OpenTelemetry disabled: $OTEL_ENDPOINT_ENV_VAR not set" }
            NoOpTelemetryProvider()
        } else {
            log.info { "OpenTelemetry enabled: exporting to $endpoint" }
            try {
                OtelTelemetryProvider()
            } catch (e: Exception) {
                log.error(e) { "Failed to initialize OpenTelemetry, falling back to no-op" }
                NoOpTelemetryProvider()
            }
        }
    }
}
