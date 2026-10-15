package com.rustyrazorblade.easydblab.services.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [ReportUnpacker]: what one upload's folder holds, read from the zip itself: every
 * file unchanged, an HTML copy of each markdown file, the generated file list, and which page
 * the pointer leads to.
 */
class ReportUnpackerTest {
    @TempDir
    lateinit var dir: File

    private fun file(
        path: String,
        text: String,
    ) {
        File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)
    }

    private fun unpack(): UnpackedReport = ReportUnpacker.unpack(ReportZip.build(dir).bytes)

    private fun UnpackedReport.text(path: String): String =
        objects
            .single { it.path == path }
            .content
            .decodeToString()

    private fun UnpackedReport.type(path: String): String = objects.single { it.path == path }.contentType

    @Test
    fun `an upload holds its files, an HTML copy of each markdown file, and the file list`() {
        file("results.md", "# Results\n\n| op | p99 |\n|---|---|\n| read | 4 |\n")
        file("graph.png", "png")

        val unpacked = unpack()

        assertThat(unpacked.objects.map { it.path }).containsExactlyInAnyOrder("results.md", "results.html", "graph.png", "_files.html")
        assertThat(unpacked.text("graph.png")).isEqualTo("png")
        assertThat(unpacked.text("results.md")).startsWith("# Results")
        assertThat(unpacked.text("results.html")).contains("<meta charset=\"utf-8\">", "<table>", "<h1>Results</h1>")
        assertThat(unpacked.type("results.html")).startsWith("text/html")
        assertThat(unpacked.type("graph.png")).isEqualTo("image/png")
    }

    @Test
    fun `a nested markdown file gets its HTML copy beside it`() {
        file("runs/r1/notes.md", "notes")

        assertThat(unpack().objects.map { it.path }).contains("runs/r1/notes.md", "runs/r1/notes.html")
    }

    @Test
    fun `the file list names every file of the upload and links each one`() {
        file("results.md", "r")
        file("runs/r1/graph.png", "g")

        val list = unpack().text("_files.html")

        assertThat(list).contains("<meta charset=\"utf-8\">")
        assertThat(list).contains("href=\"results.html\"", "href=\"results.md\"", "href=\"runs/r1/graph.png\"")
        assertThat(list.indexOf("results.md")).isLessThan(list.indexOf("runs/r1/graph.png"))
    }

    @Test
    fun `an index md at the root is the front page`() {
        file("index.md", "# Front")
        file("results.md", "r")

        val unpacked = unpack()

        assertThat(unpacked.frontPage).isEqualTo("index.html")
        assertThat(unpacked.text("index.html")).contains("<h1>Front</h1>")
    }

    @Test
    fun `an index html at the root is the front page, unchanged`() {
        file("index.html", "<p>mine</p>")

        val unpacked = unpack()

        assertThat(unpacked.frontPage).isEqualTo("index.html")
        assertThat(unpacked.text("index.html")).isEqualTo("<p>mine</p>")
    }

    @Test
    fun `without an index at the root the file list is the front page`() {
        file("sub/index.md", "nested index is not the front page")
        file("results.md", "r")

        assertThat(unpack().frontPage).isEqualTo("_files.html")
    }
}
