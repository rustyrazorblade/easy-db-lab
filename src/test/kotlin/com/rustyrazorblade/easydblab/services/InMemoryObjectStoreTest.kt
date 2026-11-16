package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The in-memory [ObjectStore]: the operations whose S3 semantics (a directory listing that stops at
 * `/`, a sibling prefix that is not listed) a test relying on it would otherwise get wrong.
 */
class InMemoryObjectStoreTest {
    private val store = InMemoryObjectStore()
    private val root = ClusterS3Path.root("bucket")

    @Test
    fun `listing a directory skips a sibling whose name starts with it`() {
        store.uploadContent("a", root.resolve("mimir/acme/one"))
        store.uploadContent("b", root.resolve("mimir/acme-dev/two"))

        val listed = store.listFiles(root.resolve("mimir/acme")).map { it.path.getKey() }

        assertThat(listed).containsExactly("mimir/acme/one")
    }

    @Test
    fun `a non-recursive listing rolls nested objects up into their directory`() {
        store.uploadContent("a", root.resolve("mimir/acme/block/meta.json"))
        store.uploadContent("b", root.resolve("mimir/top.json"))

        val listed = store.listFiles(root.resolve("mimir"), recursive = false).map { it.path.getKey() }

        assertThat(listed).containsExactlyInAnyOrder("mimir/top.json", "mimir/acme")
    }

    @Test
    fun `a deleted object no longer exists`() {
        val path = root.resolve("reports/doc.md")
        store.uploadContent("doc", path)

        store.deleteFile(path, showProgress = false)

        assertThat(store.fileExists(path)).isFalse()
    }

    @Test
    fun `a directory round trips through upload and download`(
        @TempDir dir: Path,
    ) {
        val source = dir.resolve("source").toFile().apply { resolve("nested").mkdirs() }
        source.resolve("nested/a.txt").writeText("alpha")

        val uploaded = store.uploadDirectory(source.toPath(), root.resolve("backup"), showProgress = false)
        val downloaded = store.downloadDirectory(root.resolve("backup"), dir.resolve("copy"), showProgress = false)

        assertThat(uploaded.filesUploaded).isEqualTo(1)
        assertThat(downloaded.filesDownloaded).isEqualTo(1)
        assertThat(dir.resolve("copy/nested/a.txt").toFile().readText()).isEqualTo("alpha")
    }
}
