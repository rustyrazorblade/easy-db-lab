package com.rustyrazorblade.easydblab.observability

/**
 * Factory for creating TelemetryProvider instances.
 *
 * Always creates an [OtelTelemetryProvider] backed by [io.opentelemetry.api.GlobalOpenTelemetry].
 * When the OTel Java agent is attached (container environment), it initializes the global
 * instance with the configured SDK. When running without the agent (local CLI), the global
 * instance is a no-op and all telemetry calls are zero-cost.
 */
object TelemetryFactory {
    fun create(): TelemetryProvider = OtelTelemetryProvider()
}
