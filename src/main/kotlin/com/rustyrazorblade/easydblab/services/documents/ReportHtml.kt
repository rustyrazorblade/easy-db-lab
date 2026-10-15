package com.rustyrazorblade.easydblab.services.documents

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders a report markdown file to HTML, GitHub-flavored tables included.
 *
 * The reports are the operator's own notes, so raw HTML in them is kept.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    /** The HTML body of [markdown]. */
    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown))
}

/**
 * Builds the HTML pages of a report: each markdown file's HTML copy, the generated file list and
 * the pointer.
 *
 * Every page declares UTF-8, so it reads correctly whatever charset S3 serves it with.
 */
object ReportHtml {
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

    /** [text] with the characters HTML treats as markup escaped. */
    fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
