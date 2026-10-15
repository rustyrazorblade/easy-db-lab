package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.SharedMinio
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [DefaultReportArchiveService] and `up`'s [ReportPointer.ensurePlaceholder] against MinIO, which
 * honours S3's conditional writes (LocalStack 3.0 does not): uploads are kept byte for byte, the
 * pointer moves only forward, and every page is served as HTML.
 *
 * Each test uses its own experiment, so the tests share the bucket without seeing each other's
 * reports.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportArchiveServiceIntegrationTest {
    private companion object {
        private const val BUCKET = "report-archive"
    }

    private lateinit var s3Client: S3Client
    private lateinit var objectStore: S3ObjectStore

    @BeforeAll
    fun setup() {
        s3Client = SharedMinio.s3Client()
        SharedMinio.createBucketIfMissing(s3Client, BUCKET)
        objectStore = S3ObjectStore(s3Client, EventBus())
    }

    private fun cluster(): ClusterState =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = UUID.randomUUID().toString(),
            s3Bucket = BUCKET,
            initConfig = InitConfig(tenant = "acme", experiment = "e-${UUID.randomUUID().toString().take(8)}"),
        )

    private fun service(at: String) = DefaultReportArchiveService(objectStore, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))

    private fun folder(state: ClusterState): ClusterS3Path = ObservabilityStore.from(state).documentsRoot(ExperimentKey.of(state))

    private fun bytes(path: ClusterS3Path): ByteArray =
        s3Client
            .getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(path.getKey())
                    .build(),
            ).asByteArray()

    private fun contentType(path: ClusterS3Path): String =
        s3Client
            .headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(path.getKey())
                    .build(),
            ).contentType()

    private fun pointer(state: ClusterState): String = objectStore.readContent(folder(state).resolve("index.html"))

    @Test
    fun `two uploads are both kept as built and the pointer leads to the newer`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        File(dir, "results.md").writeText("# Results\n\n| op | p99 |\n|---|---|\n| read | 4 |\n")
        File(dir, "graph.png").writeBytes(byteArrayOf(-119, 80, 78, 71))
        val firstBuilt = ReportZip.build(dir).bytes
        val first = service("2026-10-14T12:00:00Z").upload(state, dir)
        File(dir, "results.md").writeText("# Second run")
        val secondBuilt = ReportZip.build(dir).bytes

        val second = service("2026-10-14T13:00:00Z").upload(state, dir)

        assertThat(bytes(first.zip)).isEqualTo(firstBuilt)
        assertThat(bytes(second.zip)).isEqualTo(secondBuilt)
        assertThat(ReportPointer.target(pointer(state))).isEqualTo(ReportStamp("20261014-130000"))
        assertThat(pointer(state)).contains("report-20261014-130000/_files.html")
        val keys = objectStore.listFiles(folder(state)).map { it.path.getKey().removePrefix(folder(state).getKey() + "/") }
        assertThat(keys).contains(
            "report-20261014-120000.zip",
            "report-20261014-120000/results.md",
            "report-20261014-120000/results.html",
            "report-20261014-120000/graph.png",
            "report-20261014-120000/_files.html",
            "report-20261014-130000.zip",
            "index.html",
        )
        assertThat(contentType(first.unpacked.resolve("results.html"))).startsWith("text/html")
        assertThat(contentType(first.unpacked.resolve("_files.html"))).startsWith("text/html")
        assertThat(contentType(folder(state).resolve("index.html"))).startsWith("text/html")
        assertThat(contentType(first.unpacked.resolve("graph.png"))).isEqualTo("image/png")
        assertThat(String(bytes(first.unpacked.resolve("results.html")))).contains("<meta charset=\"utf-8\">", "<table>")
    }

    @Test
    fun `a taken second moves the upload to the next one and an earlier upload leaves the pointer`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        File(dir, "index.md").writeText("# Front")
        service("2026-10-14T12:00:00Z").upload(state, dir)

        val sameSecond = service("2026-10-14T12:00:00Z").upload(state, dir)
        val earlier = service("2026-10-14T11:00:00Z").upload(state, dir)

        assertThat(sameSecond.zip.getKey()).endsWith("report-20261014-120001.zip")
        assertThat(sameSecond.pointerAdvanced).isTrue()
        assertThat(earlier.pointerAdvanced).isFalse()
        assertThat(pointer(state)).contains("report-20261014-120001/index.html")
    }

    @Test
    fun `the placeholder is written only when no pointer exists`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        val placeholder = ReportPointer(objectStore)

        assertThat(placeholder.ensurePlaceholder(folder(state))).isTrue()
        assertThat(pointer(state)).contains("No report yet")
        File(dir, "results.md").writeText("x")
        service("2026-10-14T12:00:00Z").upload(state, dir)
        val afterUpload = pointer(state)

        assertThat(placeholder.ensurePlaceholder(folder(state))).isFalse()
        assertThat(pointer(state)).isEqualTo(afterUpload).contains("report-20261014-120000/_files.html")
    }
}
