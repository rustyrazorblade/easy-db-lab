package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityStageTest {
    @Test
    fun `local mode declares the in-cluster backends, and both modes the Pyroscope agent`() {
        val local = ObservabilityStage.declared(null).map { it.label }
        val redirect = ObservabilityStage.declared(TelemetryRedirect.fromBaseHost("10.0.0.9")).map { it.label }

        assertThat(local).contains("Mimir", "Loki", "Tempo", "Pyroscope", "Pyroscope Agent")
        assertThat(redirect).contains("Pyroscope Agent").doesNotContain("Mimir", "Loki", "Tempo", "Pyroscope")
    }

    @Test
    fun `the gated stages declare the capability they need`() {
        val requires = ObservabilityStage.declared(null).filter { it.requires.isNotEmpty() }.associate { it.label to it.requires }

        assertThat(requires).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "OTel Host Collector" to setOf(NodeCapability.PRIVILEGED),
                "Fluent Bit Journald" to setOf(NodeCapability.SYSTEMD_JOURNAL, NodeCapability.PRIVILEGED),
                "ebpf_exporter" to setOf(NodeCapability.PRIVILEGED),
                "S3 Manager" to setOf(NodeCapability.S3),
                "Beyla" to setOf(NodeCapability.PRIVILEGED),
                "YACE" to setOf(NodeCapability.CLOUDWATCH),
                "Pyroscope Agent" to setOf(NodeCapability.PRIVILEGED),
                "Pod logs" to setOf(NodeCapability.HOST_PATH),
            ),
        )
    }
}
