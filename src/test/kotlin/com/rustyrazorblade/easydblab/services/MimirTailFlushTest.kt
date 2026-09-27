package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * What the Mimir flush waits for, and the state it leaves Mimir in when a step fails. Mimir's HTTP
 * API and Kubernetes are faked at their boundaries.
 */
class MimirTailFlushTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state = ClusterState(name = "lab", versions = mutableMapOf(), s3Bucket = "acct")
    private val scaledDown = mutableListOf<String>()

    private val workloads =
        object : BackendWorkloads {
            override fun scaleDown(
                controlHost: ClusterHost,
                workload: String,
                timeout: Duration,
            ) {
                scaledDown.add(workload)
            }
        }

    private fun flush(
        http: RecordingObservabilityHttp,
        progress: FlushProgress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, "mimir"),
    ) = MimirTailFlush(http, workloads).flush(control, state, progress)

    @Test
    fun `the ingester shutdown ships the head, then Mimir is scaled to 0 and nothing else is called`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))

        assertThat(flush(http)).isEqualTo(SignalReport.Metrics)
        assertThat(http.calls.map { "${it.method} ${it.port} ${it.path}" }).containsExactly("POST 9009 /ingester/shutdown")
        assertThat(scaledDown).containsExactly("mimir")
    }

    @Test
    fun `a failed shutdown stops the flush there, with Mimir's ingester stopped and not scaled`() {
        val progress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, "mimir")

        assertThatThrownBy { flush(RecordingObservabilityHttp(ObservabilityResponse(503, "ingester unavailable")), progress) }
            .hasMessageContaining("503")
            .hasMessageContaining("ingester unavailable")
        assertThat(progress.step).isEqualTo(FlushStep.MIMIR_SHUTDOWN)
        assertThat(progress.backends).containsEntry("mimir", BackendState.INGESTER_STOPPED)
        assertThat(scaledDown).isEmpty()
    }
}
