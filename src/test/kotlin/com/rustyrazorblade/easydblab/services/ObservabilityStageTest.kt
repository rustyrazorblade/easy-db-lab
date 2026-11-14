package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityStageTest {
    @Test
    fun `local mode declares the in-cluster backends and redirect mode the Pyroscope agent instead`() {
        val local = ObservabilityStage.declared(null).map { it.label }
        val redirect = ObservabilityStage.declared(TelemetryRedirect.fromBaseHost("10.0.0.9")).map { it.label }

        assertThat(local).contains("Mimir", "Loki", "Tempo", "Pyroscope").doesNotContain("Pyroscope Agent")
        assertThat(redirect).contains("Pyroscope Agent").doesNotContain("Mimir", "Loki", "Tempo", "Pyroscope")
    }

    @Test
    fun `the gated stages declare the capability they need`() {
        val requires = ObservabilityStage.declared(null).filter { it.requires != null }.associate { it.label to it.requires }

        assertThat(requires).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "Fluent Bit Journald" to NodeCapability.SYSTEMD_JOURNAL,
                "S3 Manager" to NodeCapability.S3,
                "YACE" to NodeCapability.CLOUDWATCH,
            ),
        )
    }
}
