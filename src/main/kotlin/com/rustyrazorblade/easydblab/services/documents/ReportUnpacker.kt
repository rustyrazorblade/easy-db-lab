package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import java.io.ByteArrayInputStream
import java.net.URLConnection
import java.util.zip.ZipInputStream

/**
 * One object of an unpacked upload.
 *
 * @property path Its path within the upload's folder, `/`-separated.
 * @property content Its bytes.
 * @property contentType The type S3 serves it with.
 */
class ReportObject(
    val path: String,
    val content: ByteArray,
    val contentType: String,
)

/**
 * What one upload's folder holds.
 *
 * @property objects Every file of the zip unchanged, an HTML copy of each `.md`, and the file list.
 * @property frontPage The page the pointer leads to: [Constants.Observability.REPORT_FRONT_PAGE]
 *   when the upload holds `index.md` or `index.html` at its root, else the file list.
 */
class UnpackedReport(
    val objects: List<ReportObject>,
    val frontPage: String,
)

/**
 * Unpacks a report zip into the objects of its upload folder.
 *
 * It reads the zip itself, never the directory it was built from, so the folder holds exactly what
 * the stored zip holds. Each `.md` gets an HTML copy beside it, and every upload gets a generated
 * file list, [Constants.Observability.REPORT_FILE_LIST], that links each of its files. Every
 * generated page declares UTF-8.
 */
object ReportUnpacker {
    private const val HTML_SUFFIX = ".html"
    private const val GENERATED_HTML = "text/html; charset=utf-8"
    private const val FILE_LIST_TITLE = "Report files"
    private val FRONT_PAGE_SOURCES = setOf("index${Constants.Observability.REPORT_MARKDOWN_SUFFIX}", Constants.Observability.REPORT_FRONT_PAGE)

    /** The objects of the upload whose zip is [zip]. */
    fun unpack(zip: ByteArray): UnpackedReport {
        val files =
            ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
                generateSequence { stream.nextEntry }
                    .filterNot { it.isDirectory }
                    .map { ReportObject(it.name, stream.readBytes(), ReportContentTypes.of(it.name)) }
                    .toList()
            }
        val copies = files.filter { isMarkdown(it.path) }.map(::htmlCopy)
        val fileList = ReportObject(Constants.Observability.REPORT_FILE_LIST, fileList(files.map { it.path }).toByteArray(), GENERATED_HTML)
        val frontPage =
            if (files.any { it.path in FRONT_PAGE_SOURCES }) {
                Constants.Observability.REPORT_FRONT_PAGE
            } else {
                Constants.Observability.REPORT_FILE_LIST
            }
        return UnpackedReport(files + copies + fileList, frontPage)
    }

    private fun isMarkdown(path: String): Boolean = path.endsWith(Constants.Observability.REPORT_MARKDOWN_SUFFIX)

    private fun htmlName(path: String): String = path.removeSuffix(Constants.Observability.REPORT_MARKDOWN_SUFFIX) + HTML_SUFFIX

    private fun htmlCopy(markdown: ReportObject): ReportObject {
        val title = markdown.path.substringAfterLast('/').removeSuffix(Constants.Observability.REPORT_MARKDOWN_SUFFIX)
        val page = ReportHtml.page(title, MarkdownRenderer.toHtml(markdown.content.decodeToString()))
        return ReportObject(htmlName(markdown.path), page.toByteArray(), GENERATED_HTML)
    }

    private fun fileList(paths: List<String>): String {
        val items =
            paths.sorted().joinToString("\n") { path ->
                val name = ReportHtml.escape(path)
                if (isMarkdown(path)) {
                    "<li><a href=\"${ReportHtml.escape(htmlName(path))}\">$name</a> (<a href=\"$name\">source</a>)</li>"
                } else {
                    "<li><a href=\"$name\">$name</a></li>"
                }
            }
        return ReportHtml.page(FILE_LIST_TITLE, "<h1>$FILE_LIST_TITLE</h1>\n<ul>\n$items\n</ul>")
    }
}

/**
 * The content type S3 serves a report file with, from its name.
 *
 * The browser shows a report through the documents web server, which passes S3's type on, so a
 * page must be `text/html` and an image its image type. A name the JDK does not know is served as
 * `application/octet-stream`.
 */
object ReportContentTypes {
    private const val FALLBACK = "application/octet-stream"
    private val KNOWN =
        mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "md" to "text/markdown; charset=utf-8",
            "txt" to "text/plain; charset=utf-8",
            "log" to "text/plain; charset=utf-8",
            "csv" to "text/csv; charset=utf-8",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
        )

    /** The content type of a file named [path]. */
    fun of(path: String): String {
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return KNOWN[extension] ?: URLConnection.guessContentTypeFromName(path) ?: FALLBACK
    }
}
