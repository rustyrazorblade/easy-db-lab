package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Tests for [FrontmatterMarkdownLoader], the shared frontmatter-markdown parser.
 *
 * Covers stream parsing, classpath discovery, and the skip-one-not-skip-all behavior against a
 * fixture package that mixes well-formed and malformed files.
 */
class FrontmatterMarkdownLoaderTest {
    private val fixturePackage = "com.rustyrazorblade.easydblab.frontmattertest"

    @Test
    fun `parses a document with valid frontmatter and body`() {
        val content =
            """
            ---
            name: sample
            description: A sample document
            ---
            # Heading

            Body text.
            """.trimIndent()

        val document = FrontmatterMarkdownLoader().parseFromStream(ByteArrayInputStream(content.toByteArray()), "sample.md")

        assertThat(document.name).isEqualTo("sample")
        assertThat(document.description).isEqualTo("A sample document")
        assertThat(document.body).isEqualTo("# Heading\n\nBody text.")
    }

    @Test
    fun `throws when the name field is missing`() {
        val content =
            """
            ---
            description: Missing name
            ---
            Body.
            """.trimIndent()

        assertThatThrownBy {
            FrontmatterMarkdownLoader().parseFromStream(ByteArrayInputStream(content.toByteArray()), "no-name.md")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Missing required 'name' field")
    }

    @Test
    fun `throws when there is no frontmatter header`() {
        val content = "Just regular markdown without a header."

        assertThatThrownBy {
            FrontmatterMarkdownLoader().parseFromStream(ByteArrayInputStream(content.toByteArray()), "plain.md")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("frontmatter")
    }

    @Test
    fun `discovers all well-formed documents in a package`() {
        val documents = FrontmatterMarkdownLoader().loadAll(fixturePackage)

        assertThat(documents.map { it.name }).contains("good-one", "good-two")
    }

    @Test
    fun `skips a single malformed file and still loads the rest`() {
        // The fixture package contains malformed.md (no header) alongside good-one.md and good-two.md.
        val documents = FrontmatterMarkdownLoader().loadAll(fixturePackage)

        // Skip-one, not skip-all: the malformed file is dropped, the well-formed files remain.
        assertThat(documents.map { it.name }).containsExactlyInAnyOrder("good-one", "good-two")
    }

    @Test
    fun `returns an empty list for a package with no resources`() {
        val documents = FrontmatterMarkdownLoader().loadAll("com.nonexistent.package")

        assertThat(documents).isEmpty()
    }
}
