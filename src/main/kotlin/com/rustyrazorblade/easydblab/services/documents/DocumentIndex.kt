package com.rustyrazorblade.easydblab.services.documents

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * One test document rendered to HTML.
 *
 * @property name The document's heading: its file name without `.md`.
 * @property html The rendered body.
 */
data class RenderedDocument(
    val name: String,
    val html: String,
)

/**
 * Renders a test document's markdown to HTML, GitHub-flavored tables included.
 *
 * The documents are the operator's own notes, so raw HTML in them is kept.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    /** The HTML body of [markdown]. */
    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown))
}

/**
 * Builds the HTML pages of a test's documents: each document's own page, and the one `index.html`
 * Grafana shows, which holds every document in its own section, headed with its name, in name order.
 *
 * Every page declares UTF-8, because S3 serves it as `text/html` with no charset.
 */
object DocumentIndex {
    /** What the index of a test with no documents says. */
    const val EMPTY = "No documents yet"

    private const val STYLE =
        "body { font-family: sans-serif; margin: 1em; color: #222; background: #fff; } " +
            "section { margin-bottom: 2em; } " +
            "table { border-collapse: collapse; } " +
            "th, td { border: 1px solid #ccc; padding: 0.25em 0.5em; } " +
            "pre { background: #f4f4f4; padding: 0.5em; overflow: auto; }"

    /** A standalone page titled [title] holding [body]. */
    fun page(
        title: String,
        body: String,
    ): String =
        """
        |<!DOCTYPE html>
        |<html>
        |<head>
        |<meta charset="utf-8">
        |<title>${escape(title)}</title>
        |<style>$STYLE</style>
        |</head>
        |<body>
        |$body
        |</body>
        |</html>
        |
        """.trimMargin()

    /**
     * The index of a test holding [documents]. A name given twice appears once, with its last
     * content, so a replaced document is shown once.
     */
    fun render(documents: List<RenderedDocument>): String {
        val latest = documents.associateBy { it.name }.values.sortedBy { it.name }
        val body =
            if (latest.isEmpty()) {
                "<p>$EMPTY</p>"
            } else {
                latest.joinToString("\n") { "<section id=\"${escape(it.name)}\">\n<h1>${escape(it.name)}</h1>\n${it.html}</section>" }
            }
        return page("Test documents", body)
    }

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
