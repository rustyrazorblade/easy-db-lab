package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * What the Loki flush waits for, and the state it leaves Loki in when a step fails. Loki's HTTP API
 * and Kubernetes are faked at their boundaries.
 */
class LokiTailFlushTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state = ClusterState(name = "lab", versions = mutableMapOf(), s3Bucket = "acct")
    private val scaledDown = mutableListOf<String>()
    private val timeouts = FlushTimeouts(shutdown = Duration.ofSeconds(3), scaleDown = Duration.ofSeconds(4))

    private val workloads =
        object : BackendWorkloads {
            override fun scaleDown(
                controlHost: ClusterHost,
                workload: String,
                timeout: Duration,
            ) {
                scaledDown.add("$workload ${timeout.seconds}s")
            }
        }

    private fun flush(
        http: RecordingObservabilityHttp,
        progress: FlushProgress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki"),
    ) = LokiTailFlush(http, workloads, timeouts).flush(control, state, progress)

    @Test
    fun `the synchronous shutdown flushes every chunk, then Loki is scaled to 0 and nothing else is called`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))
        val progress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki")

        assertThat(flush(http, progress)).isEqualTo(SignalReport.Logs)
        assertThat(http.calls.map { "${it.method} ${it.port} ${it.path}" })
            .containsExactly("POST 3100 /ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false")
        assertThat(http.calls.single().timeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(scaledDown).containsExactly("loki 4s")
        assertThat(progress.backends).containsEntry("loki", BackendState.SCALED_TO_ZERO)
    }

    @Test
    fun `a shutdown that does not answer 204 stops the flush there, with Loki's ingester stopped`() {
        val progress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki")

        // 200 is not enough: only 204 means every flush queue drained.
        assertThatThrownBy { flush(RecordingObservabilityHttp(ObservabilityResponse(200, "flush failed")), progress) }
            .hasMessageContaining("flush failed")
        assertThat(progress.step).isEqualTo(FlushStep.LOKI_SHUTDOWN)
        assertThat(progress.backends).containsEntry("loki", BackendState.INGESTER_STOPPED)
        assertThat(scaledDown).isEmpty()
    }
}
