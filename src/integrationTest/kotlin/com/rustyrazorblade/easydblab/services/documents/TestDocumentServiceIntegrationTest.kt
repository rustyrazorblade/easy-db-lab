package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import java.io.File
import java.util.UUID

/**
 * [DefaultTestDocumentService] against S3 in LocalStack: where documents land, that a document of
 * the same name replaces the old one, and that the index holds every document of the test.
 *
 * Each test uses its own cluster id, so the tests share the bucket without seeing each other's
 * documents.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDocumentServiceIntegrationTest {
    private companion object {
        private const val BUCKET = "test-documents-bucket"
    }

    private lateinit var s3Client: S3Client
    private lateinit var objectStore: S3ObjectStore
    private lateinit var service: DefaultTestDocumentService

    @BeforeAll
    fun setup() {
        s3Client = SharedLocalStack.s3Client()
        SharedLocalStack.createBucketIfMissing(s3Client, BUCKET)
        objectStore = S3ObjectStore(s3Client, EventBus())
        service = DefaultTestDocumentService(objectStore)
    }

    private fun cluster(): ClusterState =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = UUID.randomUUID().toString(),
            s3Bucket = BUCKET,
            initConfig = InitConfig(tenant = "acme"),
        )

    private fun key(
        state: ClusterState,
        name: String,
    ): String = "reports/acme/${state.clusterLabelName()}/$name"

    private fun read(key: String): String = objectStore.readContent(ClusterS3Path.fromKey(BUCKET, key))

    private fun contentType(key: String): String =
        s3Client
            .headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build(),
            ).contentType()

    @Test
    fun `documents land in the test's folder with an HTML copy each, and the index holds them all`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        val results = File(dir, "results.md").also { it.writeText("# Results\n\n| op | p99 |\n|---|---|\n| read | 4 |\n") }
        val notes = File(dir, "notes.md").also { it.writeText("Some notes") }

        val uploaded = service.upload(state, listOf(results, notes))

        assertThat(uploaded.documents.map { it.first }).containsExactly("results.md", "notes.md")
        assertThat(read(key(state, "results.md"))).startsWith("# Results")
        assertThat(read(key(state, "results.html"))).contains("<meta charset=\"utf-8\">", "<table>")
        assertThat(contentType(key(state, "results.html"))).startsWith("text/html")
        val index = read(key(state, "index.html"))
        assertThat(uploaded.index.getKey()).isEqualTo(key(state, "index.html"))
        assertThat(index).contains("<h1>notes</h1>", "<h1>results</h1>", "Some notes").doesNotContain("No documents yet")
        assertThat(contentType(key(state, "index.html"))).startsWith("text/html")
    }

    @Test
    fun `a later upload keeps the earlier documents in the index and replaces one of the same name`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        service.upload(state, listOf(File(dir, "a.md").also { it.writeText("first a") }, File(dir, "b.md").also { it.writeText("b") }))
        val sub = File(dir, "second").also { it.mkdirs() }

        service.upload(state, listOf(File(sub, "a.md").also { it.writeText("second a") }, File(sub, "c.md").also { it.writeText("c") }))

        val index = read(key(state, "index.html"))
        assertThat(index).contains("<h1>a</h1>", "<h1>b</h1>", "<h1>c</h1>", "second a").doesNotContain("first a")
        assertThat(Regex("<h1>a</h1>").findAll(index).count()).isEqualTo(1)
        assertThat(read(key(state, "a.md"))).isEqualTo("second a")
    }

    @Test
    fun `a test with no documents gets an index that says so`() {
        val state = cluster()

        service.rebuildIndex(state)

        assertThat(read(key(state, "index.html"))).contains("No documents yet")
    }

    @Test
    fun `a rejected file stops the upload before anything is stored`(
        @TempDir dir: File,
    ) {
        val state = cluster()
        val good = File(dir, "good.md").also { it.writeText("ok") }
        val bad = File(dir, "graph.png").also { it.writeText("png") }

        assertThatThrownBy { service.upload(state, listOf(good, bad)) }
            .isInstanceOf(DocumentsRejectedException::class.java)
            .hasMessageContaining("graph.png")
        assertThat(objectStore.listFiles(ClusterS3Path.fromKey(BUCKET, "reports/acme/${state.clusterLabelName()}"))).isEmpty()
    }
}
