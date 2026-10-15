package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
    private val pointer = ReportPointer(store)

    private val older = ReportStamp.of(Instant.parse("2026-10-14T10:00:00Z"))
    private val newer = ReportStamp.of(Instant.parse("2026-10-14T11:30:05Z"))

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
    fun `an upload of the same second leaves the pointer`() {
        pointer.advance(folder, newer, "index.html")

        assertThat(pointer.advance(folder, newer, "_files.html")).isFalse()
        assertThat(pointerText()).contains("index.html")
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

        assertThat(pointer.advance(folder, older, "_files.html")).isFalse()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a write that stored the pointer but was reported refused counts as this upload's`() {
        store.loseNextWriteResponse()

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a replacing write that stored the pointer but was reported refused counts as this upload's`() {
        pointer.advance(folder, older, "_files.html")
        store.loseNextWriteResponse()

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a first-attempt refusal whose re-read names this stamp is another writer's pointer, not this upload's`() {
        pointer.advance(folder, older, "_files.html")
        store.beforeNextWrite { store.overwrite(pointerPath, ReportPointer.render(newer, "index.html")) }

        assertThat(pointer.advance(folder, newer, "_files.html")).isFalse()
        assertThat(pointerText()).contains("report-20261014-113005/index.html")
    }

    @Test
    fun `a write refused after a retry whose re-read names an older upload is decided again and written`() {
        pointer.advance(folder, older, "_files.html")
        store.refuseAfterRetryAt(pointerPath) { store.overwrite(pointerPath, ReportPointer.render(older, "index.html")) }

        assertThat(pointer.advance(folder, newer, "_files.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a write refused after a retry whose re-read names a newer upload leaves that pointer`() {
        val newest = newer.next()
        pointer.advance(folder, older, "_files.html")
        store.refuseAfterRetryAt(pointerPath) { store.overwrite(pointerPath, ReportPointer.render(newest, "index.html")) }

        assertThat(pointer.advance(folder, newer, "_files.html")).isFalse()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newest)
    }

    @Test
    fun `a pointer that keeps changing under the writer fails instead of looping forever`() {
        store.beforeEveryWrite { store.overwrite(pointerPath, "changed ${System.nanoTime()}") }

        assertThatThrownBy { pointer.advance(folder, newer, "index.html") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(pointerPath.toUri())
    }

    @Test
    fun `a write that meets its own earlier attempt in flight counts as this upload's once it lands`() {
        store.conflictAfterRetryAt(pointerPath, landsAfterReads = 1)

        assertThat(pointer.advance(folder, newer, "index.html")).isTrue()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newer)
    }

    @Test
    fun `a last write refused after a retry whose pointer names this upload counts as written`() {
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
    fun `a last write refused after a retry whose pointer names a newer upload leaves that pointer`() {
        val newest = newer.next()
        var writes = 0
        store.beforeEveryWrite {
            writes++
            if (writes < Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
                store.overwrite(pointerPath, "changed $writes")
            } else {
                store.refuseAfterRetryAt(pointerPath) { store.overwrite(pointerPath, ReportPointer.render(newest, "index.html")) }
            }
        }

        assertThat(pointer.advance(folder, newer, "index.html")).isFalse()
        assertThat(ReportPointer.target(pointerText())).isEqualTo(newest)
    }

    @Test
    fun `a last write refused after a retry whose pointer names another upload still fails`() {
        var writes = 0
        store.beforeEveryWrite {
            writes++
            if (writes < Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
                store.overwrite(pointerPath, "changed $writes")
            } else {
                store.refuseAfterRetryAt(pointerPath) { store.overwrite(pointerPath, ReportPointer.render(older, "index.html")) }
            }
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
}
