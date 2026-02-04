package com.rustyrazorblade.easydblab.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource

/**
 * Builds OpenTelemetry resource with proper attribute precedence.
 *
 * Resource attributes are merged with the following precedence (highest to lowest):
 * 1. Environment variables (OTEL_RESOURCE_ATTRIBUTES, OTEL_SERVICE_NAME)
 * 2. Default application attributes (service.name, service.version, host.name)
 *
 * This allows external tools to override default attributes by setting environment
 * variables, which is essential for multi-tenant or dynamic deployment scenarios.
 */
object OtelResourceBuilder {
    /**
     * Builds a resource by merging environment-provided attributes with defaults.
     *
     * Environment attributes take precedence over defaults, allowing customization
     * via OTEL_RESOURCE_ATTRIBUTES without code changes.
     *
     * @param envResource Resource containing attributes from environment variables
     *                    (provided by AutoConfiguredOpenTelemetrySdk)
     * @return Merged resource with env vars overriding defaults
     */
    fun buildResource(envResource: Resource): Resource {
        val defaults =
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("service.name"),
                    TelemetryNames.SERVICE_NAME,
                    AttributeKey.stringKey("service.version"),
                    getVersion(),
                    AttributeKey.stringKey("host.name"),
                    getHostName(),
                ),
            )

        // Merge order: defaults first, then env vars
        // This ensures env vars override defaults when keys conflict
        return defaults.merge(envResource)
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
}
