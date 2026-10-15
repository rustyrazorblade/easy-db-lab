package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
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
        DefaultReportArchiveService(store, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))

    /** A service whose upload is [uploadId], so the zip it builds is `ReportZip.build(dir, uploadId)`. */
    private fun service(
        at: String,
        uploadId: String,
    ): DefaultReportArchiveService = DefaultReportArchiveService(store, Clock.fixed(Instant.parse(at), ZoneOffset.UTC)) { uploadId }

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
    fun `an unpacked file refused on its first attempt fails the upload even when it holds the same bytes`() {
        file("results.md", "mine")
        store.overwrite(key("reports/acme/e1/report-20261014-120000/results.md"), "mine")

        assertThatThrownBy { service("2026-10-14T12:00:00Z").upload(state, dir) }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasMessageContaining("already exists")
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
    fun `a zip write that was stored but reported refused keeps its name and is not stored again`() {
        file("results.md", "mine")
        store.loseWriteResponseAt(key("reports/acme/e1/report-20261014-120000.zip"))

        val uploaded = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(uploaded.zip.toUri()).isEqualTo(zipUri)
        assertThat(uploaded.pointerAdvanced).isTrue()
        assertThat(store.keys()).noneMatch { it.startsWith("reports/acme/e1/report-20261014-120001") }
    }

    @Test
    fun `an unpacked file write that was stored but reported refused is this upload's own`() {
        file("results.md", "mine")
        store.loseWriteResponseAt(key("reports/acme/e1/report-20261014-120000/results.md"))

        val uploaded = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(uploaded.pointerAdvanced).isTrue()
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("mine")
    }

    @Test
    fun `a zip write refused after a retry because another upload's zip got there moves to the next second`() {
        file("results.md", "mine")
        val taken = key("reports/acme/e1/report-20261014-120000.zip")
        store.refuseAfterRetryAt(taken) { store.overwrite(taken, "another upload's zip") }

        val uploaded = service("2026-10-14T12:00:00Z").upload(state, dir)

        assertThat(uploaded.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120001.zip")
        assertThat(store.bytesAt(taken)?.decodeToString()).isEqualTo("another upload's zip")
        assertThat(text("reports/acme/e1/report-20261014-120001/results.md")).isEqualTo("mine")
    }

    @Test
    fun `a zip write refused after a retry because another upload of the same directory got there moves to the next second`() {
        file("results.md", "same")
        val taken = key("reports/acme/e1/report-20261014-120000.zip")
        val othersZip = ReportZip.build(dir, "another-upload").bytes
        store.refuseAfterRetryAt(taken) { store.overwrite(taken, othersZip) }

        val uploaded = service("2026-10-14T12:00:00Z", "upload-1").upload(state, dir)

        assertThat(uploaded.zip.getKey()).isEqualTo("reports/acme/e1/report-20261014-120001.zip")
        assertThat(store.bytesAt(taken)).isEqualTo(othersZip)
        assertThat(store.bytesAt(uploaded.zip)).isEqualTo(ReportZip.build(dir, "upload-1").bytes)
    }

    @Test
    fun `an unpacked file write refused after a retry because another writer's file got there fails the upload`() {
        file("results.md", "mine")
        val taken = key("reports/acme/e1/report-20261014-120000/results.md")
        store.refuseAfterRetryAt(taken) { store.overwrite(taken, "another writer's") }

        assertThatThrownBy { service("2026-10-14T12:00:00Z").upload(state, dir) }
            .isInstanceOf(ReportUploadIncompleteException::class.java)
            .hasMessageContaining("already exists")
        assertThat(text("reports/acme/e1/report-20261014-120000/results.md")).isEqualTo("another writer's")
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
}
