package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * The writer's Tempo is stopped only once every span it took is in a backend block (spike S3): a
 * graceful stop alone cancels the upload and leaves a partial block. The drain waits, as
 * `TempoTailFlush` does, for no live traces and a steady created-traces counter, then for the
 * `flushed` marker of every completed block under the live store's WAL in the work directory.
 */
class WriterTempoDrainTest {
    @TempDir
    lateinit var wal: File

    private var live = 0
    private var created = 3
    private var readings = 0
    private var onReading: (Int) -> Unit = {}

    private val backends =
        FakeBackends { call ->
            check(call.route == "/metrics")
            onReading(++readings)
            ObservabilityResponse(
                200,
                """
                tempo_live_store_complete_queue_length 0
                tempo_live_store_live_traces{tenant="acme"} $live
                tempo_live_store_traces_created_total{tenant="acme"} $created
                """.trimIndent(),
            )
        }

    private fun drain(timeout: Duration = Duration.ofSeconds(5)) =
        WriterTempoDrain(backends.forTenant("acme"), wal, timeout, pollInterval = Duration.ofMillis(5)).await()

    private fun block(
        tenant: String,
        id: String,
        flushed: Boolean,
    ) {
        File(wal, "blocks/$tenant/$id").mkdirs()
        File(wal, "blocks/$tenant/$id/meta.json").writeText("{}")
        if (flushed) File(wal, "blocks/$tenant/$id/flushed").writeText("")
    }

    @Test
    fun `the drain returns once traces are idle and every completed block has its flushed marker`() {
        block("acme", "b1", flushed = true)
        block("acme", "b2", flushed = false)
        // Tempo writes the marker once the upload is done, after the traces went idle.
        val upload =
            Thread {
                Thread.sleep(100)
                File(wal, "blocks/acme/b2/flushed").writeText("")
            }
        upload.start()

        drain()

        assertThat(File(wal, "blocks/acme/b2/flushed")).exists()
        upload.join()
    }

    @Test
    fun `live traces keep the drain waiting until they are cut`() {
        live = 2
        onReading = { n -> if (n == 4) live = 0 }

        drain()

        assertThat(readings).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `a block never flushed fails naming it, so the writer is not stopped with a partial block`() {
        block("acme", "stuck", flushed = false)

        assertThatThrownBy { drain(Duration.ofMillis(200)) }.hasMessageContaining("blocks/acme/stuck")
    }

    @Test
    fun `a WAL block not yet completed keeps the drain waiting`() {
        File(wal, "head1").mkdirs()
        File(wal, "head1/meta.json").writeText("{}")

        assertThatThrownBy { drain(Duration.ofMillis(200)) }.hasMessageContaining("head1")
    }
}
