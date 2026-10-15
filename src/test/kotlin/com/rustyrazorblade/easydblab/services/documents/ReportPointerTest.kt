package com.rustyrazorblade.easydblab.services.documents

import ch.qos.logback.classic.Level
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration
import java.time.Instant

/**
 * Tests for [ReportPointer]: the pointer only moves forward, a concurrent writer is re-read and
 * retried, and `up`'s placeholder never replaces a pointer.
 *
 * The store is an in-memory [ConditionalObjectStore] with S3's conditional semantics, so the
 * pointer's decisions are tested without S3; the real conditional writes are tested against MinIO.
 */
class ReportPointerTest {
    private val folder = ClusterS3Path.fromKey("acct", "reports/acme/e1")
    private val pointerPath = folder.resolve("index.html")
    private val store = InMemoryConditionalObjectStore()
    private val pointer = ReportPointer(store, Duration.ZERO)

    private val older = ReportStamp.of(Instant.parse("2026-10-14T10:00:00Z"))
    private val newer = ReportStamp.of(Instant.parse("2026-10-14T11:30:05Z"))
    private val maxLooks = Constants.Observability.REPORT_IN_FLIGHT_MAX_LOOKS

    private fun pointerText(): String = checkNotNull(store.readVersioned(pointerPath)).content

    @Test
    fun `a stamp is the UTC second and names the zip and the folder`() {
        assertThat(newer.timestamp).isEqualTo("20261014-113005")
        assertThat(newer.zipName).isEqualTo("report-20261014-113005.zip")
        assertThat(newer.folderName).isEqualTo("report-20261014-113005")
        assertThat(newer.next().timestamp).isEqualTo("20261014-113006")
        assertThat(older).isLessThan(newer)
    }

    @Test
    fun `the first upload writes the pointer to its front page`() {
        val advanced = pointer.advance(folder, newer, "_files.html")

        assertThat(advanced).isTrue()
        assertThat(pointerText()).contains("report-20261014-113005/_files.html", "<meta charset=\"utf-8\">")
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `an index front page is the pointer's target`() {
        pointer.advance(folder, newer, "index.html")

        assertThat(pointerText()).contains("report-20261014-113005/index.html").doesNotContain("_files.html")
    }

    @Test
    fun `a newer upload replaces an older pointer`() {
        pointer.advance(folder, older, "_files.html")

        assertThat(pointer.advance(folder, newer, "_files.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `an older upload does not move the pointer back`() {
        pointer.advance(folder, newer, "index.html")
        val before = pointerText()

        assertThat(pointer.advance(folder, older, "_files.html")).isFalse()
        assertThat(pointerText()).isEqualTo(before)
    }

    @Test
    fun `a pointer that already names this upload's stamp counts as written and is left unchanged`() {
        pointer.advance(folder, newer, "index.html")
        val before = pointerText()

        assertThat(pointer.advance(folder, newer, "_files.html")).isTrue()
        assertThat(pointerText()).isEqualTo(before)
    }

    @Test
    fun `a concurrent writer of an older upload is re-read and this upload still wins`() {
        pointer.ensurePlaceholder(folder)
        store.beforeNextWrite { store.overwrite(pointerPath, ReportPointer.render(older, "_files.html")) }

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(store.refusedWrites).isEqualTo(1)
    }

    @Test
    fun `a concurrent writer of a newer upload is re-read and kept`() {
        store.beforeNextWrite { store.overwrite(pointerPath, ReportPointer.render(newer, "index.html")) }

        val logs = CapturedLogs.during(DOCUMENTS_LOGGER) { assertThat(pointer.advance(folder, older, "_files.html")).isFalse() }

        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(logs.at(Level.INFO)).contains("Pointer ${pointerPath.toUri()} decided after 1 refused writes: newer")
    }

    @Test
    fun `a write that stored the pointer but was reported refused counts as this upload's`() {
        store.loseNextWriteResponse()

        val logs = CapturedLogs.during(DOCUMENTS_LOGGER) { assertThat(pointer.advance(folder, newer, "index.html")).isTrue() }

        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(logs.at(Level.INFO)).contains("Pointer ${pointerPath.toUri()} decided after 1 refused writes: own")
    }

    @Test
    fun `a replacing write that stored the pointer but was reported refused counts as this upload's`() {
        pointer.advance(folder, older, "_files.html")
        store.loseNextWriteResponse()

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a refused write whose re-read names this upload's stamp counts as written, whatever the refusal`() {
        pointer.advance(folder, older, "_files.html")
        store.beforeNextWrite { store.overwrite(pointerPath, ReportPointer.render(newer, "index.html")) }

        assertThat(pointer.advance(folder, newer, "_files.html")).isTrue()
        assertThat(pointerText()).contains("report-20261014-113005/index.html")
    }

    @Test
    fun `a pointer that keeps changing under the writer fails instead of looping forever`() {
        store.beforeEveryWrite { store.overwrite(pointerPath, "changed ${System.nanoTime()}") }

        assertThatThrownBy { pointer.advance(folder, newer, "index.html") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(pointerPath.toUri())
            .hasMessageContaining("waits for a write in flight that gave up: 0")
            .hasMessageContaining("this upload is stored at ${folder.resolve(newer.folderName).toUri()} but not pointed at")
            .hasMessageNotContaining("report upload again")
    }

    @Test
    fun `a write that meets this upload's own write in flight waits for it to land and counts it as written`() {
        pointer.advance(folder, older, "_files.html")
        store.writeInFlightAt(pointerPath, endsOnRead = 4, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        val logs = CapturedLogs.during(DOCUMENTS_LOGGER) { assertThat(pointer.advance(folder, newer, "index.html")).isTrue() }

        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(logs.at(Level.INFO)).anySatisfy { assertThat(it).contains(pointerPath.toUri(), "settled") }
    }

    @Test
    fun `a first write that meets this upload's own write in flight counts it as written once it lands`() {
        store.writeInFlightAt(pointerPath, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a write in flight that lands as a newer upload's pointer is left alone`() {
        val newest = ReportPointer.render(newer.next(), "index.html").toByteArray()
        pointer.advance(folder, older, "_files.html")
        store.writeInFlightAt(pointerPath, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOther(newest))

        assertThat(pointer.advance(folder, newer, "index.html")).isFalse()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer.next())
    }

    @Test
    fun `a wait for a write in flight that gives up logs it and the next attempt writes the pointer`() {
        pointer.advance(folder, older, "_files.html")
        // The top read, then every look of the wait: the write in flight aborts on the last look.
        store.writeInFlightAt(pointerPath, endsOnRead = 1 + maxLooks, InMemoryConditionalObjectStore.InFlightEnd.Aborts)

        val logs = CapturedLogs.during(DOCUMENTS_LOGGER) { assertThat(pointer.advance(folder, newer, "index.html")).isTrue() }

        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(logs.at(Level.WARN)).anySatisfy {
            assertThat(it).contains(pointerPath.toUri(), "gave up", "$maxLooks looks")
        }
    }

    @Test
    fun `a pointer whose write in flight never lands fails saying the in-flight waits gave up`() {
        pointer.advance(folder, older, "_files.html")
        store.writeInFlightAt(pointerPath, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)

        assertThatThrownBy { pointer.advance(folder, newer, "index.html") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(pointerPath.toUri())
            .hasMessageContaining("waits for a write in flight that gave up: ${Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS}")
        assertThat(ReportPointer.target(pointerText())).isEqualTo(older)
    }

    @Test
    fun `an S3 error on a read while waiting for a write in flight fails at once, after exactly that read`() {
        val error =
            S3Exception
                .builder()
                .statusCode(500)
                .message("read failed")
                .build()
        pointer.advance(folder, older, "_files.html")
        val readsBefore = store.readsOf(pointerPath)
        store.writeInFlightAt(pointerPath, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)
        // The top read, then the first look of the wait, which fails.
        store.failReadAt(pointerPath, readNumber = readsBefore + 2, error)

        assertThatThrownBy { pointer.advance(folder, newer, "index.html") }.isSameAs(error)
        assertThat(store.readsOf(pointerPath)).isEqualTo(readsBefore + 2)
    }

    @Test
    fun `a last write whose response was lost counts as written on the final re-read`() {
        var writes = 0
        store.beforeEveryWrite {
            writes++
            if (writes < Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
                store.overwrite(pointerPath, "changed $writes")
            } else {
                store.loseNextWriteResponse()
            }
        }

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
        assertThat(writes).isEqualTo(Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS)
    }

    @Test
    fun `a final re-read that names a newer upload leaves that pointer`() {
        val newest = newer.next()
        var writes = 0
        store.beforeEveryWrite {
            writes++
            val page =
                if (writes <
                    Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS
                ) {
                    "changed $writes"
                } else {
                    ReportPointer.render(newest, "index.html")
                }
            store.overwrite(pointerPath, page)
        }

        assertThat(pointer.advance(folder, newer, "index.html")).isFalse()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newest)
    }

    @Test
    fun `a final re-read that names an older upload still fails`() {
        var writes = 0
        store.beforeEveryWrite {
            writes++
            val page =
                if (writes <
                    Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS
                ) {
                    "changed $writes"
                } else {
                    ReportPointer.render(older, "index.html")
                }
            store.overwrite(pointerPath, page)
        }

        assertThatThrownBy { pointer.advance(folder, newer, "index.html") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(pointerPath.toUri())
        assertThat(ReportPointer.target(pointerText())).isEqualTo(older)
    }

    @Test
    fun `the placeholder says no report yet and is replaced by the first upload`() {
        pointer.ensurePlaceholder(folder)

        assertThat(pointerText()).contains("No report yet", "<meta charset=\"utf-8\">")
        assertThat(ReportPointer.target(pointerText())).isNull()

        assertThat(pointer.advance(folder, older, "_files.html")).isTrue()
    }

    @Test
    fun `the placeholder never replaces an existing pointer`() {
        pointer.advance(folder, newer, "index.html")
        val before = pointerText()

        pointer.ensurePlaceholder(folder)

        assertThat(pointerText()).isEqualTo(before)
    }

    @Test
    fun `a placeholder write that stored the pointer but was reported refused leaves the placeholder`() {
        store.loseNextWriteResponse()

        pointer.ensurePlaceholder(folder)

        assertThat(pointerText()).contains("No report yet")
    }

    private companion object {
        private const val DOCUMENTS_LOGGER = "com.rustyrazorblade.easydblab.services.documents"
    }
}
