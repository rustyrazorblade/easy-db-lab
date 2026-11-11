package com.rustyrazorblade.easydblab.services.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [DocumentNames], the rule `report upload` applies to every file before it uploads any.
 */
class DocumentNamesTest {
    @TempDir
    lateinit var dir: File

    private fun file(name: String): File = File(dir, name).also { it.writeText("# $name") }

    private fun rejectedNames(vararg files: File): List<String> = DocumentNames.rejected(files.toList()).map { it.name }

    @Test
    fun `markdown files with safe names are accepted`() {
        assertThat(DocumentNames.rejected(listOf(file("results.md"), file("run_2.notes-v1.md")))).isEmpty()
    }

    @Test
    fun `a file that is not markdown is rejected`() {
        assertThat(rejectedNames(file("graph.png"), file("results.md"))).containsExactly("graph.png")
    }

    @Test
    fun `a name outside the character set is rejected`() {
        assertThat(rejectedNames(file("my notes.md"), file("résumé.md"))).containsExactly("my notes.md", "résumé.md")
    }

    @Test
    fun `index md is rejected because it would collide with the index`() {
        val rejected = DocumentNames.rejected(listOf(file("index.md")))

        assertThat(rejected).singleElement().satisfies({ assertThat(it.name).isEqualTo("index.md") })
    }

    @Test
    fun `the rejection of index md names the file once`() {
        val message = DocumentsRejectedException(DocumentNames.rejected(listOf(file("index.md")))).message

        assertThat(message).isEqualTo("Nothing was uploaded. Rejected: index.md is reserved for the test's index")
    }

    @Test
    fun `a name with no stem is rejected`() {
        assertThat(rejectedNames(file(".md"))).containsExactly(".md")
    }

    @Test
    fun `a missing file and a directory are rejected`() {
        val directory = File(dir, "notes.md").also { it.mkdirs() }

        assertThat(rejectedNames(File(dir, "missing.md"), directory)).containsExactly("missing.md", "notes.md")
    }

    @Test
    fun `every rejected file is reported with a reason`() {
        val rejected = DocumentNames.rejected(listOf(file("a.txt"), file("index.md"), File(dir, "gone.md")))

        assertThat(rejected.map { it.name }).containsExactly("a.txt", "index.md", "gone.md")
        assertThat(rejected).allSatisfy { assertThat(it.reason).isNotBlank() }
    }

    @Test
    fun `the same file name given twice in one upload is rejected`() {
        val first = File(dir, "a").also { it.mkdirs() }.resolve("results.md").also { it.writeText("a") }
        val second = File(dir, "b").also { it.mkdirs() }.resolve("results.md").also { it.writeText("b") }

        val rejected = DocumentNames.rejected(listOf(first, second, file("notes.md")))

        assertThat(rejected).singleElement().satisfies({
            assertThat(it.name).isEqualTo("results.md")
            assertThat(it.reason).contains("given twice")
        })
    }
}
