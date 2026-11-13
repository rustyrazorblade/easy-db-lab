package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the one place that writes the core collector's in-cluster addresses, so every producer that
 * dials the Service agrees on the short `.svc` name and the OTLP ports.
 */
class OtelEndpointsTest {
    @Test
    fun `the service host is the short svc name in the given namespace`() {
        assertThat(OtelEndpoints.serviceHost("default")).isEqualTo("otel-collector.default.svc")
        assertThat(OtelEndpoints.serviceHost("obs")).isEqualTo("otel-collector.obs.svc")
    }

    @Test
    fun `the host uses the default namespace when none is given`() {
        assertThat(OtelEndpoints.serviceHost())
            .isEqualTo("otel-collector.${Constants.K8s.NAMESPACE}.svc")
    }

    @Test
    fun `the OTLP endpoints address the service host on the OTLP ports`() {
        assertThat(OtelEndpoints.otlpHttpEndpoint("default"))
            .isEqualTo("http://otel-collector.default.svc:${Constants.K8s.OTEL_HTTP_PORT}")
        assertThat(OtelEndpoints.otlpGrpcEndpoint("default"))
            .isEqualTo("http://otel-collector.default.svc:${Constants.K8s.OTEL_GRPC_PORT}")
    }
}
