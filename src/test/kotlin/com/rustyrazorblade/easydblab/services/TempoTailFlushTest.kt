package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * When the Tempo drain decides every received span is in S3. Tempo's `/metrics` is answered from a
 * queue, and the control node's WAL listing (over SSH) is faked.
 */
class TempoTailFlushTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state = ClusterState(name = "lab", versions = mutableMapOf())
    private val remoteOps = mock<RemoteOperationsService>()

    private fun metrics(
        created: Int,
        live: Int = 0,
    ) = ObservabilityResponse(
        200,
        """
        # TYPE tempo_live_store_live_traces gauge
        tempo_live_store_live_traces{tenant="acme"} $live
        tempo_live_store_live_traces{tenant="other"} 0
        tempo_live_store_traces_created_total{tenant="acme"} $created
        tempo_live_store_failed_flushes_total 3
        """.trimIndent(),
    )

    private fun walListing(vararg listings: String) {
        val stub = whenever(remoteOps.executeRemotely(any(), any(), any(), any()))
        listings.fold(stub) { acc, listing -> acc.thenReturn(Response(listing)) }
    }

    private fun drain(
        http: RecordingObservabilityHttp,
        timeout: Duration = Duration.ofSeconds(5),
        progress: FlushProgress = FlushProgress(FlushStep.TEMPO_LIVE_TRACES, "tempo"),
    ) = TempoTailFlush(http, remoteOps, FlushTimeouts(tempoDrain = timeout), pollInterval = Duration.ofMillis(10))
        .flush(control, state, progress)

    @Test
    fun `a metric series is read per label set`() {
        val body = metrics(created = 7, live = 2).body

        assertThat(TempoTailFlush.series(body, TempoTailFlush.LIVE_TRACES))
            .containsExactlyInAnyOrderEntriesOf(mapOf("{tenant=\"acme\"}" to 2.0, "{tenant=\"other\"}" to 0.0))
        assertThat(TempoTailFlush.series(body, TempoTailFlush.TRACES_CREATED).values).containsExactly(7.0)
    }

    @Test
    fun `the drain waits while traces are live or still being created, then for every block to be flushed`() {
        val http =
            RecordingObservabilityHttp(
                metrics(created = 5, live = 1),
                metrics(created = 6),
                // A reading of 0 whose created count still moved can be stale: keep waiting.
                metrics(created = 7),
                metrics(created = 7),
            )
        walListing(
            "01HWAL+acme+vParquet4/meta.json\nblocks/acme/01HDONE/meta.json\nblocks/acme/01HDONE/flushed",
            "blocks/acme/01HDONE/meta.json\nblocks/acme/01HDONE/flushed\nblocks/acme/01HNEW/meta.json",
            "blocks/acme/01HDONE/meta.json\nblocks/acme/01HDONE/flushed\nblocks/acme/01HNEW/meta.json\nblocks/acme/01HNEW/flushed",
        )

        assertThat(drain(http)).isEqualTo(SignalReport.Traces(2))
        assertThat(http.calls).hasSize(4)
    }

    @Test
    fun `a Tempo still receiving spans times out, reporting live traces and the failure counters`() {
        val http = RecordingObservabilityHttp(*(1..1000).map { metrics(created = it, live = 1) }.toTypedArray())
        val progress = FlushProgress(FlushStep.TEMPO_LIVE_TRACES, "tempo")

        assertThatThrownBy { drain(http, timeout = Duration.ofMillis(200), progress = progress) }
            .hasMessageContaining("still receives or holds traces")
            .hasMessageContaining("tenant=\"acme\"")
            .hasMessageContaining("tempo_live_store_failed_flushes_total")
        assertThat(progress.step).isEqualTo(FlushStep.TEMPO_LIVE_TRACES)
        assertThat(progress.backends).containsEntry("tempo", BackendState.RUNNING)
    }

    /** What Tempo 3.0.3 exposes before its first span: the live store's metrics, but no per-tenant series. */
    private val noTenantYet =
        ObservabilityResponse(
            200,
            """
            tempo_live_store_blocks_completed_total 0
            tempo_live_store_complete_queue_length 0
            tempo_live_store_failed_completions_total 0
            tempo_live_store_ready 1
            """.trimIndent(),
        )

    @Test
    fun `a Tempo that never received a span has no live-traces series and drains at once`() {
        walListing("")

        assertThat(drain(RecordingObservabilityHttp(noTenantYet, noTenantYet))).isEqualTo(SignalReport.Traces(0))
    }

    @Test
    fun `an exposition with neither the live-traces series nor the live store's queue fails the drain, naming both`() {
        val notTempo = ObservabilityResponse(200, "go_goroutines 12\nprocess_open_fds 40")
        val http = RecordingObservabilityHttp(*(1..1000).map { notTempo }.toTypedArray())
        val progress = FlushProgress(FlushStep.TEMPO_LIVE_TRACES, "tempo")
        walListing("")

        assertThatThrownBy { drain(http, timeout = Duration.ofMillis(200), progress = progress) }
            .hasMessageContaining(TempoTailFlush.LIVE_TRACES)
            .hasMessageContaining(TempoTailFlush.COMPLETE_QUEUE)
        assertThat(progress.step).isEqualTo(FlushStep.TEMPO_LIVE_TRACES)
    }

    @Test
    fun `an unflushed block past the timeout fails the block wait and names it`() {
        val http = RecordingObservabilityHttp(metrics(created = 1), metrics(created = 1), metrics(created = 1))
        walListing("blocks/acme/01HSTUCK/meta.json")

        assertThatThrownBy { drain(http, timeout = Duration.ofMillis(200)) }
            .hasMessageContaining("blocks/acme/01HSTUCK")
    }

    @Test
    fun `an empty head with no meta json and a completed WAL block are drained`() {
        assertThat(TempoTailFlush.drained(listOf())).isTrue()
        // Completion renames the WAL block's meta.json to meta.deleted.json, which the listing does not match.
        assertThat(TempoTailFlush.drained(listOf("blocks/acme/01HDONE/meta.json", "blocks/acme/01HDONE/flushed"))).isTrue()
    }

    @Test
    fun `a head block with a meta json is not drained`() {
        assertThat(TempoTailFlush.drained(listOf("01HWAL+acme+vParquet4/meta.json"))).isFalse()
        assertThat(TempoTailFlush.pendingWalBlocks(listOf("01HWAL+acme+vParquet4/meta.json"))).containsExactly("01HWAL+acme+vParquet4")
    }

    @Test
    fun `a local block with a meta json and no flushed marker is not drained`() {
        val listing = listOf("blocks/acme/01HA/meta.json", "blocks/acme/01HA/flushed", "blocks/acme/01HB/meta.json")

        assertThat(TempoTailFlush.drained(listing)).isFalse()
        assertThat(TempoTailFlush.unflushedBlocks(listing)).containsExactly("blocks/acme/01HB")
    }
}
