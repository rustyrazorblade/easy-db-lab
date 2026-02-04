package com.rustyrazorblade.easydblab.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for OtelResourceBuilder to verify resource attribute merging behavior.
 *
 * Key behavior: Environment variable attributes should take precedence over
 * hardcoded defaults. This allows external tools to customize telemetry
 * attributes via OTEL_RESOURCE_ATTRIBUTES.
 */
class OtelResourceBuilderTest {
    @Test
    fun `buildResource should include default service name when no env override`() {
        val envResource = Resource.empty()

        val result = OtelResourceBuilder.buildResource(envResource)

        assertThat(result.getAttribute(AttributeKey.stringKey("service.name")))
            .isEqualTo(TelemetryNames.SERVICE_NAME)
    }

    @Test
    fun `buildResource should include default service version`() {
        val envResource = Resource.empty()

        val result = OtelResourceBuilder.buildResource(envResource)

        assertThat(result.getAttribute(AttributeKey.stringKey("service.version")))
            .isNotNull()
    }

    @Test
    fun `buildResource should include default host name`() {
        val envResource = Resource.empty()

        val result = OtelResourceBuilder.buildResource(envResource)

        assertThat(result.getAttribute(AttributeKey.stringKey("host.name")))
            .isNotNull()
    }

    @Test
    fun `buildResource should allow env vars to override service name`() {
        val customServiceName = "custom-service-from-env"
        val envResource =
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("service.name"),
                    customServiceName,
                ),
            )

        val result = OtelResourceBuilder.buildResource(envResource)

        assertThat(result.getAttribute(AttributeKey.stringKey("service.name")))
            .isEqualTo(customServiceName)
    }

    @Test
    fun `buildResource should allow env vars to override host name`() {
        val customHostName = "custom-host-from-env"
        val envResource =
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("host.name"),
                    customHostName,
                ),
            )

        val result = OtelResourceBuilder.buildResource(envResource)

        assertThat(result.getAttribute(AttributeKey.stringKey("host.name")))
            .isEqualTo(customHostName)
    }

    @Test
    fun `buildResource should preserve custom env attributes alongside defaults`() {
        val envResource =
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("custom.attribute"),
                    "custom-value",
                    AttributeKey.stringKey("cluster.id"),
                    "my-cluster-123",
                ),
            )

        val result = OtelResourceBuilder.buildResource(envResource)

        // Custom attributes from env should be present
        assertThat(result.getAttribute(AttributeKey.stringKey("custom.attribute")))
            .isEqualTo("custom-value")
        assertThat(result.getAttribute(AttributeKey.stringKey("cluster.id")))
            .isEqualTo("my-cluster-123")

        // Default attributes should also be present
        assertThat(result.getAttribute(AttributeKey.stringKey("service.name")))
            .isEqualTo(TelemetryNames.SERVICE_NAME)
    }

    @Test
    fun `buildResource should allow partial override of defaults`() {
        // Override only service.name, leave host.name as default
        val envResource =
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("service.name"),
                    "overridden-service",
                ),
            )

        val result = OtelResourceBuilder.buildResource(envResource)

        // service.name should be overridden
        assertThat(result.getAttribute(AttributeKey.stringKey("service.name")))
            .isEqualTo("overridden-service")

        // host.name should still be the default (non-null)
        assertThat(result.getAttribute(AttributeKey.stringKey("host.name")))
            .isNotNull()
    }
}
