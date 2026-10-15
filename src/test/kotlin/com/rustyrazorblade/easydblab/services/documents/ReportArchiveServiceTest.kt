package com.rustyrazorblade.easydblab.services.documents

import ch.qos.logback.classic.Level
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests for [DefaultReportArchiveService]: where an upload lands, that it never replaces an
 * earlier one, that a taken second moves to the next free one, and that the pointer only moves
 * forward. The store is the in-memory [InMemoryConditionalObjectStore]; the same flow runs against
 * MinIO in `ReportArchiveServiceIntegrationTest`.
 */
class ReportArchiveServiceTest {
    @TempDir
    lateinit var dir: File

    private val store = InMemoryConditionalObjectStore()
    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "abc",
            s3Bucket = "acct",
            initConfig = InitConfig(tenant = "acme", experiment = "e1"),
        )

    private fun service(at: String): DefaultReportArchiveService =
        DefaultReportArchiveService(store, Clock.fixed(Instant.parse(at), ZoneOffset.UTC), Duration.ZERO)

    /** A service whose upload is [uploadId], so the zip it builds is `ReportZip.build(dir, uploadId)`. */
    private fun service(
        at: String,
        uploadId: String,
    ): DefaultReportArchiveService =
        DefaultReportArchiveService(store, Clock.fixed(Instant.parse(at), ZoneOffset.UTC), Duration.ZERO) {
            uploadId
        }

    private fun file(
        path: String,
        text: String,
    ) {
        File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)
    }

    private fun key(path: String) = ClusterS3Path.fromKey("acct", path)

    private fun text(path: String): String = checkNotNull(store.bytesAt(key(path))).decodeToString()

    @Test
    fun `an upload stores the zip as built, unpacks it beside it and points at it`() {
        file("results.md", "# Results")
        file("graph.png", "png")

        val uploaded = service("2026-10-14T12:00:00Z", "upload-1").upload(state, dir)

        assertThat(uploaded.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120000.zip")
        assertThat(uploaded.unpacked.getKey()).isEqualTo("reports/acme/e1/report-20261014-120000")
        assertThat(uploaded.pointerAdvanced).isTrue()
        assertThat(uploaded.files).containsExactly("graph.png", "results.md")
        assertThat(store.bytesAt(uploaded.zip)).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
        assertThat(store.contentTypeAt(uploaded.zip)).isEqualTo("application/zip")
        assertThat(store.keys()).containsExactly(
            "reports/acme/e1/index.html",
            "reports/acme/e1/report-20261014-120000.zip",
            "reports/acme/e1/report-20261014-120000/_files.html",
            "reports/acme/e1/report-20261014-120000/graph.png",
            "reports/acme/e1/report-20261014-120000/results.html",
            "reports/acme/e1/report-20261014-120000/results.md",
        )
        assertThat(text("reports/acme/e1/index.html")).contains("report-20261014-120000/_files.html")
        assertThat(store.contentTypeAt(key("reports/acme/e1/report-20261014-120000/results.html"))).startsWith("text/html")
    }

    @Test
    fun `an upload with an index md leads the pointer to its index page`() {
        file("index.md", "# Front")

        service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(text("reports/acme/e1/index.html")).contains("report-20261014-120000/index.html")
    }

    @Test
    fun `a second upload keeps the first and the pointer shows the newer`() {
        file("results.md", "first")
        val first = service("2026-10-14T12:00:00Z").upload(state, dir)
        val firstZip = checkNotNull(store.bytesAt(first.zip))
        file("results.md", "second")

        val second = service("2026-10-14T13:00:00Z").upload(state, dir)

        assertThat(store.bytesAt(first.zip)).isEqualTo(firstZip)
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("first")
        assertThat(text("reports/acme/e1/report-20261014-130000/results.md")).isEqualTo("second")
        assertThat(second.pointerAdvanced).isTrue()
        assertThat(ReportPointer.target(text("reports/acme/e1/index.html"))).isEqualTo(ReportStamp("20261014-130000"))
    }

    @Test
    fun `an upload in a second that is taken moves to the next free second`() {
        file("results.md", "first")
        service("2026-10-14T12:00:00Z").upload(state, dir)
        file("results.md", "second")
        service("2026-10-14T12:00:00Z").upload(state, dir)
        file("results.md", "third")

        val third = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(third.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120002.zip")
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("first")
        assertThat(text("reports/acme/e1/report-20261014-120002/results.md")).isEqualTo("third")
    }

    @Test
    fun `a second upload of an unchanged directory in the same second keeps the first and takes the next second`() {
        file("results.md", "same")
        val first = service("2026-10-14T12:00:00Z").upload(state, dir)

        val second = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(first.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120000.zip")
        assertThat(second.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120001.zip")
        assertThat(store.bytesAt(second.zip)).isNotEqualTo(store.bytesAt(first.zip))
        assertThat(text("reports/acme/e1/report-20261014-120001/results.md")).isEqualTo("same")
        assertThat(second.pointerAdvanced).isTrue()
        assertThat(ReportPointer.target(text("reports/acme/e1/index.html"))).isEqualTo(ReportStamp("20261014-120001"))
    }

    @Test
    fun `an upload named for an earlier second does not move the pointer back`() {
        file("results.md", "later")
        service("2026-10-14T13:00:00Z").upload(state, dir)

        val earlier = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(earlier.pointerAdvanced).isFalse()
        assertThat(store.bytesAt(earlier.zip)).isNotNull()
        assertThat(ReportPointer.target(text("reports/acme/e1/index.html"))).isEqualTo(ReportStamp("20261014-130000"))
    }

    @Test
    fun `a rejected directory writes nothing`() {
        file("my notes.md", "x")

        assertThatThrownBy { service("2026-10-14T12:00:00Z").upload(state, dir) }
            .isInstanceOf(ReportRejectedException::class.java)
            .hasMessageContaining("my notes.md")
        assertThat(store.keys()).isEmpty()
    }

    @Test
    fun `a cluster from before experiments uploads into its own folder`() {
        file("results.md", "x")

        val uploaded = service("2026-10-14T12:00:00Z").upload(state.copy(initConfig = InitConfig(tenant = "acme")), dir)

        assertThat(uploaded.zip.getKey()).isEqualTo("reports/acme/lab-abc/report-20261014-120000.zip")
    }

    private val zipUri = "s3://acct/reports/acme/e1/report-20261014-120000.zip"

    @Test
    fun `an upload that cannot unpack keeps its zip and says to run again`() {
        file("results.md", "mine")
        store.overwrite(key("reports/acme/e1/report-20261014-120000/results.md"), "another writer's")

        assertThatThrownBy { service("2026-10-14T12:00:00Z", "upload-1").upload(state, dir) }
            .isInstanceOfSatisfying(ReportUploadIncompleteException::class.java) {
                assertThat(it.zip.toUri()).isEqualTo(zipUri)
            }.hasMessageContaining(zipUri)
            .hasMessageContaining("Run report upload again")
        assertThat(store.bytesAt(key("reports/acme/e1/report-20261014-120000.zip"))).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("another writer's")
    }

    @Test
    fun `an upload whose pointer keeps changing keeps its zip and says to run again`() {
        file("results.md", "mine")
        val pointer = key("reports/acme/e1/index.html")
        store.beforeEveryWrite { store.overwrite(pointer, "<html>another writer's page</html>") }

        assertThatThrownBy { service("2026-10-14T12:00:00Z", "upload-1").upload(state, dir) }
            .isInstanceOfSatisfying(ReportUploadIncompleteException::class.java) {
                assertThat(it.zip.toUri()).isEqualTo(zipUri)
            }.hasMessageContaining(zipUri)
            .hasMessageContaining("Run report upload again")
        assertThat(store.bytesAt(key("reports/acme/e1/report-20261014-120000.zip"))).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
        assertThat(text("reports/acme/e1/index.html")).isEqualTo("<html>another writer's page</html>")
    }

    @Test
    fun `an interrupt while unpacking reaches the caller as an interrupt, not as an incomplete upload`() {
        file("results.md", "mine")
        val interrupt = InterruptedException("interrupted while waiting on a write in flight")
        var writes = 0
        // The first write is the zip; the second, an unpacked file, is interrupted.
        store.beforeEveryWrite { if (++writes == 2) throw interrupt }

        assertThatThrownBy { upload() }.isSameAs(interrupt)
    }

    private val zipKey = key("reports/acme/e1/report-20261014-120000.zip")
    private val fileKey = key("reports/acme/e1/report-20261014-120000/results.md")
    private val maxLooks = Constants.Observability.REPORT_IN_FLIGHT_MAX_LOOKS

    private fun noSuchBucket(): S3Exception =
        S3Exception
            .builder()
            .statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
            .build() as S3Exception

    private fun upload(): UploadedReport = service("2026-10-14T12:00:00Z", "upload-1").upload(state, dir)

    private fun assertKeptItsSecond(uploaded: UploadedReport) {
        assertThat(uploaded.zip).isEqualTo(zipKey)
        assertThat(store.bytesAt(zipKey)).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
        assertThat(store.keys()).noneMatch { it.startsWith("reports/acme/e1/report-20261014-120001") }
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("mine")
        assertThat(uploaded.pointerAdvanced).isTrue()
    }

    private fun assertMovedToNextSecond(uploaded: UploadedReport) {
        assertThat(uploaded.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120001.zip")
        assertThat(store.bytesAt(uploaded.zip)).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
        assertThat(text("reports/acme/e1/report-20261014-120001/results.md")).isEqualTo("mine")
    }

    // A refused zip write: what the key holds decides.

    @Test
    fun `a zip write that was stored but reported refused keeps its name and is not stored again`() {
        file("results.md", "mine")
        store.loseWriteResponseAt(zipKey)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `a zip key that already holds exactly this upload's bytes is this upload's own`() {
        file("results.md", "mine")
        store.overwrite(zipKey, ReportZip.build(dir, "upload-1").bytes)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `a zip key that holds another upload's zip of the same directory moves the upload to the next second`() {
        file("results.md", "mine")
        val othersZip = ReportZip.build(dir, "another-upload").bytes
        store.beforeNextWrite { store.overwrite(zipKey, othersZip) }

        assertMovedToNextSecond(upload())
        assertThat(store.bytesAt(zipKey)).isEqualTo(othersZip)
    }

    @Test
    fun `a zip key that is empty until this upload's own write in flight lands keeps its second`() {
        file("results.md", "mine")
        store.writeInFlightAt(zipKey, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `a zip key whose write in flight aborts is written again by this upload and keeps its second`() {
        file("results.md", "mine")
        store.writeInFlightAt(zipKey, endsOnRead = 1, InMemoryConditionalObjectStore.InFlightEnd.Aborts)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `a zip key whose write in flight lands as another upload's moves the upload to the next second`() {
        file("results.md", "mine")
        store.writeInFlightAt(zipKey, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOther("other".toByteArray()))

        assertMovedToNextSecond(upload())
        assertThat(store.bytesAt(zipKey)?.decodeToString()).isEqualTo("other")
    }

    @Test
    fun `a zip key still empty after every look moves the upload on and warns that the key may still be written`() {
        file("results.md", "mine")
        store.writeInFlightAt(zipKey, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)
        lateinit var uploaded: UploadedReport

        val logs = CapturedLogs.during(DOCUMENTS_LOGGER) { uploaded = upload() }

        assertMovedToNextSecond(uploaded)
        assertThat(logs.at(Level.WARN)).anySatisfy {
            assertThat(it).contains(zipKey.toUri(), "$maxLooks looks", "may still receive a write in flight")
        }
    }

    @Test
    fun `a zip re-put refused on the last look is read back in that look`() {
        file("results.md", "mine")
        // Each look reads the empty key, writes it again and, refused, reads it back: this upload's
        // own write lands on the read back of the last look.
        store.writeInFlightAt(zipKey, endsOnRead = 2 * maxLooks, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `an S3 error while waiting on an empty zip key fails at once, without looking again`() {
        file("results.md", "mine")
        val error = noSuchBucket()
        store.writeInFlightAt(zipKey, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)
        store.failReadAt(zipKey, readNumber = 1, error)

        assertThatThrownBy { upload() }.isSameAs(error)
        assertThat(store.readsOf(zipKey)).isEqualTo(1)
    }

    // A refused unpacked-file write: what the key holds decides the same way.

    @Test
    fun `an unpacked file write that was stored but reported refused is this upload's own`() {
        file("results.md", "mine")
        store.loseWriteResponseAt(fileKey)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `an unpacked file key that holds another writer's object fails the upload and is left unchanged`() {
        file("results.md", "mine")
        store.overwrite(fileKey, "another writer's")

        assertThatThrownBy { upload() }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasMessageContaining(fileKey.toUri())
            .hasMessageContaining("another object holds that key")
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("another writer's")
    }

    @Test
    fun `an unpacked file key that is empty until this upload's own write in flight lands completes the upload`() {
        file("results.md", "mine")
        store.writeInFlightAt(fileKey, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `an unpacked file key whose write in flight aborts is written again and completes the upload`() {
        file("results.md", "mine")
        store.writeInFlightAt(fileKey, endsOnRead = 1, InMemoryConditionalObjectStore.InFlightEnd.Aborts)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `an unpacked file key whose write in flight lands as another writer's fails the upload`() {
        file("results.md", "mine")
        store.writeInFlightAt(fileKey, endsOnRead = 3, InMemoryConditionalObjectStore.InFlightEnd.LandsOther("other".toByteArray()))

        assertThatThrownBy { upload() }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasMessageContaining("another object holds that key")
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("other")
    }

    @Test
    fun `an unpacked file key still empty after every look fails the upload saying so`() {
        file("results.md", "mine")
        store.writeInFlightAt(fileKey, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)

        assertThatThrownBy { upload() }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasMessageContaining(fileKey.toUri())
            .hasMessageContaining("still empty after $maxLooks looks")
            .hasMessageNotContaining("already exists")
    }

    @Test
    fun `an unpacked file re-put refused on the last look is read back in that look`() {
        file("results.md", "mine")
        store.writeInFlightAt(fileKey, endsOnRead = 2 * maxLooks, InMemoryConditionalObjectStore.InFlightEnd.LandsOwn)

        assertKeptItsSecond(upload())
    }

    @Test
    fun `an S3 error while waiting on an empty unpacked file key fails the upload at once`() {
        file("results.md", "mine")
        val error = noSuchBucket()
        store.writeInFlightAt(fileKey, endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)
        store.failReadAt(fileKey, readNumber = 1, error)

        assertThatThrownBy { upload() }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasCause(error)
        assertThat(store.readsOf(fileKey)).isEqualTo(1)
    }

    @Test
    fun `an upload whose every name is taken fails, names the last name tried and writes nothing`() {
        file("results.md", "x")
        generateSequence(ReportStamp("20261014-120000")) { it.next() }
            .take(Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS)
            .forEach { store.overwrite(key("reports/acme/e1/${it.zipName}"), "another upload") }
        val before = store.keys()

        assertThatThrownBy { service("2026-10-14T12:00:00Z").upload(state, dir) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("nothing was uploaded")
            .hasMessageContaining("up to report-20261014-120059.zip")
        assertThat(store.keys()).isEqualTo(before)
    }

    @Test
    fun `an upload whose every name is held or still empty fails naming how many of each`() {
        file("results.md", "x")
        val stamps =
            generateSequence(ReportStamp("20261014-120000")) { it.next() }
                .take(Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS)
                .toList()
        stamps.drop(1).forEach { store.overwrite(key("reports/acme/e1/${it.zipName}"), "another upload") }
        store.writeInFlightAt(key("reports/acme/e1/${stamps.first().zipName}"), endsOnRead = Int.MAX_VALUE, InMemoryConditionalObjectStore.InFlightEnd.Aborts)
        val held = Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS - 1

        assertThatThrownBy { service("2026-10-14T12:00:00Z").upload(state, dir) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(
                "No report name for ${Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS} seconds up to " +
                    "report-20261014-120059.zip could be claimed",
            ).hasMessageContaining("$held held by other uploads, 1 still empty after a write in flight")
            .hasMessageContaining("nothing was uploaded")
            .hasMessageNotContaining("is taken")
    }

    private companion object {
        private const val DOCUMENTS_LOGGER = "com.rustyrazorblade.easydblab.services.documents"
    }
}
