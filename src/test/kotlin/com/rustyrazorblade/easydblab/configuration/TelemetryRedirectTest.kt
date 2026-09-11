package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies endpoint derivation, per-signal override replacement, and well-formedness validation
 * for [TelemetryRedirect]. The traces assertion guards the port trap: OTLP is 4320, never 3200.
 */
class TelemetryRedirectTest {
    @Test
    fun `fromBaseHost derives all four endpoints from the base host and known ports`() {
        val redirect = TelemetryRedirect.fromBaseHost("10.0.0.5")

        assertThat(redirect.metrics).isEqualTo("http://10.0.0.5:8428/api/v1/write")
        assertThat(redirect.logs).isEqualTo("http://10.0.0.5:9428/insert/opentelemetry")
        assertThat(redirect.traces).isEqualTo("10.0.0.5:4320")
        assertThat(redirect.profiles).isEqualTo("http://10.0.0.5:4040")
    }

    @Test
    fun `derived traces endpoint uses the OTLP port 4320, not the query port 3200`() {
        val redirect = TelemetryRedirect.fromBaseHost("tempo.example.com")

        assertThat(redirect.traces).endsWith(":4320")
        assertThat(redirect.traces).doesNotContain("3200")
    }

    @Test
    fun `per-signal override replaces only that derived endpoint`() {
        val redirect =
            TelemetryRedirect.fromBaseHost(
                baseHost = "10.0.0.5",
                tracesOverride = "otel.example.com:4317",
            )

        assertThat(redirect.traces).isEqualTo("otel.example.com:4317")
        // The other three remain derived from the base host.
        assertThat(redirect.metrics).isEqualTo("http://10.0.0.5:8428/api/v1/write")
        assertThat(redirect.logs).isEqualTo("http://10.0.0.5:9428/insert/opentelemetry")
        assertThat(redirect.profiles).isEqualTo("http://10.0.0.5:4040")
    }

    @Test
    fun `validate accepts well-formed derived endpoints`() {
        val redirect = TelemetryRedirect.fromBaseHost("10.0.0.5")

        assertThat(redirect.validate()).isEmpty()
    }

    @Test
    fun `validate names each missing signal`() {
        val redirect = TelemetryRedirect(metrics = "", logs = "", traces = "", profiles = "")

        assertThat(redirect.validate())
            .containsExactly("metrics", "logs", "traces", "profiles")
    }

    @Test
    fun `validate flags a malformed metrics URL without a scheme`() {
        val redirect =
            TelemetryRedirect.fromBaseHost("10.0.0.5").copy(metrics = "10.0.0.5:8428/api/v1/write")

        assertThat(redirect.validate()).containsExactly("metrics")
    }

    @Test
    fun `validate flags a traces endpoint that carries a scheme`() {
        val redirect =
            TelemetryRedirect.fromBaseHost("10.0.0.5").copy(traces = "http://10.0.0.5:4320")

        assertThat(redirect.validate()).containsExactly("traces")
    }

    @Test
    fun `validate flags a traces endpoint with a non-numeric port`() {
        val redirect =
            TelemetryRedirect.fromBaseHost("10.0.0.5").copy(traces = "10.0.0.5:otlp")

        assertThat(redirect.validate()).containsExactly("traces")
    }

    @Test
    fun `validate flags a profiles endpoint missing a host`() {
        val redirect =
            TelemetryRedirect.fromBaseHost("10.0.0.5").copy(profiles = "http://:4040")

        assertThat(redirect.validate()).containsExactly("profiles")
    }
}
