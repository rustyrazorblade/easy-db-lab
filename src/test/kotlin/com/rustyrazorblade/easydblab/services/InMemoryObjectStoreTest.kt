package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The `ObjectStore` contract, held by the in-memory adapter the unit tests store objects in. */
class InMemoryObjectStoreTest {
    private val store = InMemoryObjectStore()
    private val root = ClusterStoragePath.root("bucket")

    @TempDir
    lateinit var tempDir: Path

    private fun keys(files: List<ObjectStore.FileInfo>) = files.map { it.path.getKey() }

    /** The listing cases `S3ObjectStore` also runs against LocalStack. */
    @Nested
    inner class Listing : ObjectStoreListingContract() {
        override val store = InMemoryObjectStore()

        override fun freshRoot(): ClusterStoragePath = root.resolve("contract")
    }

    @Test
    fun `content round-trips`() {
        store.uploadContent("hello", root.resolve("a/greeting.txt"))

        assertThat(store.readContent(root.resolve("a/greeting.txt"))).isEqualTo("hello")
    }

    @Test
    fun `reading a missing object throws`() {
        assertThatThrownBy { store.readContent(root.resolve("missing")) }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessageContaining("bucket/missing")
    }

    @Test
    fun `objects in another bucket are not visible`() {
        store.uploadContent("x", ClusterStoragePath.root("other").resolve("a/1"))

        assertThat(store.fileExists(root.resolve("a/1"))).isFalse()
        assertThat(store.listFiles(root.resolve("a"))).isEmpty()
    }

    @Test
    fun `listing follows key order and startAfter`() {
        listOf("a/3", "a/1", "a/2").forEach { store.uploadContent(it, root.resolve(it)) }

        assertThat(keys(store.listFiles(root.resolve("a"), startAfter = "a/1"))).containsExactly("a/2", "a/3")
    }

    @Test
    fun `a listing never includes a sibling whose name begins with the directory's`() {
        store.uploadContent("x", root.resolve("acme/1"))
        store.uploadContent("y", root.resolve("acme-dev/1"))

        assertThat(keys(store.listFiles(root.resolve("acme")))).containsExactly("acme/1")
    }

    @Test
    fun `a non-recursive listing returns direct children and rolls deeper keys up into their directory`() {
        store.uploadContent("1", root.resolve("a/1"))
        store.uploadContent("2", root.resolve("a/b/2"))
        store.uploadContent("3", root.resolve("a/b/3"))

        val listed = store.listFiles(root.resolve("a"), recursive = false)

        assertThat(keys(listed)).containsExactly("a/1", "a/b")
        assertThat(keys(listed)).doesNotContain("a/b/2", "a/b/3")
        assertThat(listed.single { it.path.getKey() == "a/b" }.size).isZero()
    }

    @Test
    fun `a recursive listing returns every key under the directory with its size`() {
        store.uploadContent("1", root.resolve("a/1"))
        store.uploadContent("22", root.resolve("a/b/2"))

        val listed = store.listFiles(root.resolve("a"))

        assertThat(listed.map { it.path.getKey() to it.size }).containsExactly("a/1" to 1L, "a/b/2" to 2L)
    }

    @Test
    fun `listing the bucket root lists every key`() {
        store.uploadContent("1", root.resolve("a/1"))
        store.uploadContent("2", root.resolve("b"))

        assertThat(keys(store.listFiles(root))).containsExactly("a/1", "b")
    }

    @Test
    fun `existence and metadata reflect what is stored`() {
        store.uploadContent("abc", root.resolve("f"))

        assertThat(store.fileExists(root.resolve("f"))).isTrue()
        assertThat(store.getFileInfo(root.resolve("f"))?.size).isEqualTo(3L)
        assertThat(store.getFileInfo(root.resolve("g"))).isNull()
        assertThat(store.directoryExists(root)).isTrue()
        assertThat(store.directoryExists(root.resolve("f"))).isFalse()
    }

    @Test
    fun `delete removes the object`() {
        store.uploadContent("x", root.resolve("f"))

        store.deleteFile(root.resolve("f"))

        assertThat(store.fileExists(root.resolve("f"))).isFalse()
    }

    @Test
    fun `a file uploads and downloads, replacing an existing local file`() {
        val local = File(tempDir.toFile(), "in.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uploaded = store.uploadFile(local, root.resolve("bin/in.bin"))
        val target = tempDir.resolve("out.bin").also { it.toFile().writeText("stale") }

        val downloaded = store.downloadFile(root.resolve("bin/in.bin"), target)

        assertThat(uploaded.fileSize).isEqualTo(3L)
        assertThat(downloaded.fileSize).isEqualTo(3L)
        assertThat(target.toFile().readBytes()).containsExactly(1, 2, 3)
    }

    @Test
    fun `uploading a missing local file fails`() {
        assertThatThrownBy { store.uploadFile(File(tempDir.toFile(), "nope"), root.resolve("x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `downloading a missing object throws`() {
        assertThatThrownBy { store.downloadFile(root.resolve("missing"), tempDir.resolve("out")) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `a directory uploads and downloads with its layout`() {
        val source = tempDir.resolve("src").toFile().apply { mkdirs() }
        File(source, "top.txt").writeText("t")
        File(source, "nested").mkdirs()
        File(source, "nested/deep.txt").writeText("dd")

        val up = store.uploadDirectory(source.toPath(), root.resolve("dir"))
        val target = tempDir.resolve("dst")
        val down = store.downloadDirectory(root.resolve("dir"), target)

        assertThat(up.filesUploaded).isEqualTo(2)
        assertThat(up.totalBytes).isEqualTo(3L)
        assertThat(keys(store.listFiles(root.resolve("dir")))).containsExactly("dir/nested/deep.txt", "dir/top.txt")
        assertThat(down.filesDownloaded).isEqualTo(2)
        assertThat(target.resolve("top.txt").toFile().readText()).isEqualTo("t")
        assertThat(target.resolve("nested/deep.txt").toFile().readText()).isEqualTo("dd")
    }

    /** A caller that compares upload times (EMR's final stderr) needs them to come from a clock it sets. */
    @Test
    fun `an object's last-modified time is the store clock's time when it was written`() {
        val writtenAt = Instant.parse("2026-09-28T14:08:45Z")
        val clocked = InMemoryObjectStore(Clock.fixed(writtenAt, ZoneOffset.UTC))
        clocked.uploadContent("x", root.resolve("a/1"))

        assertThat(clocked.getFileInfo(root.resolve("a/1"))?.lastModified).isEqualTo(writtenAt.toString())
        assertThat(clocked.listFiles(root.resolve("a")).single().lastModified).isEqualTo(writtenAt.toString())
    }

    @Test
    fun `uriOf names the in-memory store`() {
        assertThat(store.uriOf(root.resolve("a/b"))).isEqualTo("mem://bucket/a/b")
    }

    @Test
    fun `a stored key keeps the store prefix it was written under`() {
        val prefixed = ClusterStoragePath.root("bucket", prefix = "team-a")
        store.uploadContent("x", prefixed.resolve("reports/doc.md"))

        assertThat(keys(store.listFiles(prefixed.resolve("reports")))).containsExactly("team-a/reports/doc.md")
        assertThat(store.readContent(root.resolve("team-a/reports/doc.md"))).isEqualTo("x")
    }
}
