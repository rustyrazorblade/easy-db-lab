package com.rustyrazorblade.easydblab.services.documents

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Tests for [ReportZip]: every rule a report directory must meet before anything is built, that
 * every offender is named at once, and that the zip holds exactly the directory's files.
 */
class ReportZipTest {
    @TempDir
    lateinit var dir: File

    private fun file(
        path: String,
        text: String = "# $path",
    ): File = File(dir, path).also { it.parentFile.mkdirs() }.also { it.writeText(text) }

    private fun rejected(): List<String> = ReportZip.problems(dir).map { it.path }

    private fun entries(archive: ReportArchive): Map<String, String> =
        ZipInputStream(ByteArrayInputStream(archive.bytes)).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().decodeToString() }
        }

    @Test
    fun `a directory of servable files of any type is accepted`() {
        file("results.md")
        file("graph.png")
        file("runs/run_1/notes-v2.md")
        file("index.md")

        assertThat(ReportZip.problems(dir)).isEmpty()
    }

    @Test
    fun `a missing directory is named with its directory form`() {
        val missing = File(dir, "missing")

        val problems = ReportZip.problems(missing)

        assertThat(problems.map { it.path }).containsExactly("${missing.path}/")
        assertThat(problems.single().reason).contains("does not exist")
    }

    @Test
    fun `a file given in place of a directory is refused`() {
        val single = file("a.md")

        assertThat(ReportZip.problems(single).single().reason).contains("is not a directory")
    }

    @Test
    fun `an empty directory is named`() {
        File(dir, "only-dirs/nested").mkdirs()

        val problems = ReportZip.problems(dir)

        assertThat(problems.map { it.path }).containsExactly("${dir.path}/")
        assertThat(problems.single().reason).contains("holds no file")
    }

    @Test
    fun `a path segment outside the character set is refused at any depth`() {
        file("my notes.md")
        file("ok.md")
        file("sub dir/inner.md")
        file("résumé.md")

        assertThat(rejected()).containsExactly("my notes.md", "résumé.md", "sub dir/inner.md")
    }

    @Test
    fun `a path holding two dots is refused`() {
        file("notes..md")
        file("ok.md")

        assertThat(rejected()).containsExactly("notes..md")
    }

    @Test
    fun `a symbolic link is refused and not followed`() {
        val target = file("real.md")
        Files.createSymbolicLink(File(dir, "link.md").toPath(), target.toPath())
        val outside = File(dir.parentFile, "outside-${dir.name}").also { it.mkdirs() }
        File(outside, "secret.md").writeText("x")
        Files.createSymbolicLink(File(dir, "linked-dir").toPath(), outside.toPath())

        assertThat(rejected()).containsExactly("link.md", "linked-dir")
    }

    @Test
    fun `the generated file list name is reserved at the root only`() {
        file("_files.html")
        file("sub/_files.html")
        file("ok.md")

        assertThat(rejected()).containsExactly("_files.html")
    }

    @Test
    fun `a markdown file beside an HTML file of the same stem names both, at any depth`() {
        file("x.md")
        file("x.html")
        file("sub/y.md")
        file("sub/y.html")
        file("z.md")
        file("w.html")

        assertThat(rejected()).containsExactly("sub/y.html", "sub/y.md", "x.html", "x.md")
    }

    @Test
    fun `an index md with an index html is refused, either alone is the front page`() {
        file("index.md")
        file("index.html")

        assertThat(rejected()).containsExactly("index.html", "index.md")
    }

    @Test
    fun `every offender is named in one failure and nothing is built`() {
        file("a b.md")
        Files.createSymbolicLink(File(dir, "link.md").toPath(), file("target.md").toPath())
        file("x.md")
        file("x.html")

        assertThatThrownBy { ReportZip.build(dir) }
            .isInstanceOf(ReportRejectedException::class.java)
            .hasMessageContaining("a b.md")
            .hasMessageContaining("link.md")
            .hasMessageContaining("x.md")
            .hasMessageContaining("x.html")
            .hasMessageContaining("Nothing was uploaded")
    }

    @Test
    fun `the zip holds every file under its relative path, in path order`() {
        file("results.md", "# Results")
        file("runs/r1/graph.png", "png-bytes")
        file("index.md", "front")

        val archive = ReportZip.build(dir)

        assertThat(archive.files).containsExactly("index.md", "results.md", "runs/r1/graph.png")
        assertThat(entries(archive))
            .containsExactlyEntriesOf(mapOf("index.md" to "front", "results.md" to "# Results", "runs/r1/graph.png" to "png-bytes"))
    }

    @Test
    fun `building the same directory twice gives the same bytes`() {
        file("results.md", "# Results")
        file("runs/r1/graph.png", "png-bytes")
        val first = ReportZip.build(dir).bytes
        Thread.sleep(2_100)

        assertThat(ReportZip.build(dir).bytes).isEqualTo(first)
    }
}
