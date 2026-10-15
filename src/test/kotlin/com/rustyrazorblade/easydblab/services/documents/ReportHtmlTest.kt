package com.rustyrazorblade.easydblab.services.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [ReportHtml], the page every generated report HTML file uses, and [MarkdownRenderer].
 */
class ReportHtmlTest {
    @Test
    fun `markdown tables render as HTML tables`() {
        val html = MarkdownRenderer.toHtml("| op | p99 |\n|----|-----|\n| read | 4 ms |\n")

        assertThat(html).contains("<table>", "<td>read</td>", "<td>4 ms</td>")
    }

    @Test
    fun `a document page declares UTF-8 and holds the rendered body`() {
        val page = ReportHtml.page("results", MarkdownRenderer.toHtml("# Results\n\nµs are fine"))

        assertThat(page).contains("<meta charset=\"utf-8\">", "<h1>Results</h1>", "µs are fine")
    }
}
