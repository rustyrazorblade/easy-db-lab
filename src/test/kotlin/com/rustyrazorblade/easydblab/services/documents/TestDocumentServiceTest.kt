package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for [DefaultTestDocumentService] when an upload fails part-way: the error names the file that
 * failed and every document already stored, since those are in S3 but not yet in the index.
 *
 * S3 is faked at the [ObjectStore] boundary; the happy path runs against LocalStack in
 * `TestDocumentServiceIntegrationTest`.
 */
class TestDocumentServiceTest {
    @TempDir
    lateinit var dir: File

    private val objectStore = mock<ObjectStore>()

    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "abc",
            s3Bucket = "acct",
            initConfig = InitConfig(tenant = "acme"),
        )

    @Test
    fun `a failed upload names the file that failed and the documents already stored`() {
        whenever(objectStore.uploadFile(any(), argThat<ClusterS3Path> { getKey().endsWith("/b.md") }, any()))
            .thenThrow(IllegalStateException("S3 refused the write"))
        val files = listOf("a.md", "b.md", "c.md").map { name -> File(dir, name).also { it.writeText(name) } }

        assertThatThrownBy { DefaultTestDocumentService(objectStore).upload(state, files) }
            .isInstanceOf(DocumentUploadFailedException::class.java)
            .hasMessageContaining("b.md")
            .hasMessageContaining("S3 refused the write")
            .satisfies({
                val failure = it as DocumentUploadFailedException
                assertThat(failure.failed).isEqualTo("b.md")
                assertThat(failure.stored).containsExactly("a.md")
                assertThat(failure.message).contains("a.md")
            })
    }
}
