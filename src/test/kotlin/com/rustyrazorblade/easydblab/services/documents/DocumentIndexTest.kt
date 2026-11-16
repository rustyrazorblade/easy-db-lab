package com.rustyrazorblade.easydblab.services.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [DocumentIndex], the one `index.html` of a test, and [MarkdownRenderer].
 */
class DocumentIndexTest {
    @Test
    fun `an empty test says no documents yet`() {
        val html = DocumentIndex.render(emptyList())

        assertThat(html).contains("<meta charset=\"utf-8\">", "No documents yet")
    }

    @Test
    fun `one document gets one section headed with its name`() {
        val html = DocumentIndex.render(listOf(RenderedDocument("results", "<p>fast</p>")))

        assertThat(html).contains("<h1>results</h1>", "<p>fast</p>").doesNotContain("No documents yet")
        assertThat(Regex("<section").findAll(html).count()).isEqualTo(1)
    }

    @Test
    fun `several documents appear in name order`() {
        val html =
            DocumentIndex.render(
                listOf(RenderedDocument("c", "<p>C</p>"), RenderedDocument("a", "<p>A</p>"), RenderedDocument("b", "<p>B</p>")),
            )

        assertThat(html.indexOf("<h1>a</h1>")).isLessThan(html.indexOf("<h1>b</h1>"))
        assertThat(html.indexOf("<h1>b</h1>")).isLessThan(html.indexOf("<h1>c</h1>"))
    }

    @Test
    fun `a document listed twice appears once, with its last content`() {
        val html = DocumentIndex.render(listOf(RenderedDocument("results", "<p>old</p>"), RenderedDocument("results", "<p>new</p>")))

        assertThat(Regex("<h1>results</h1>").findAll(html).count()).isEqualTo(1)
        assertThat(html).contains("<p>new</p>").doesNotContain("<p>old</p>")
    }

    @Test
    fun `markdown tables render as HTML tables`() {
        val html = MarkdownRenderer.toHtml("| op | p99 |\n|----|-----|\n| read | 4 ms |\n")

        assertThat(html).contains("<table>", "<td>read</td>", "<td>4 ms</td>")
    }

    @Test
    fun `a document page declares UTF-8 and holds the rendered body`() {
        val page = DocumentIndex.page("results", MarkdownRenderer.toHtml("# Results\n\nµs are fine"))

        assertThat(page).contains("<meta charset=\"utf-8\">", "<h1>Results</h1>", "µs are fine")
    }
}
