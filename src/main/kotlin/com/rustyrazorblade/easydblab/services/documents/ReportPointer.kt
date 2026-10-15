package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore

/**
 * The pointer of an experiment's report folder, `<folder>/index.html`: the page the documents
 * panels show, which leads to the newest upload's front page.
 *
 * The pointer is derived, and it is the only object under the folder that is ever rewritten. It
 * only moves forward: it names its upload's [ReportStamp] in a comment line, and an upload leaves a
 * pointer that names an equal or newer one. Every write is conditional on the version that was
 * read, so of two uploads that finish together the newer wins whatever their order; a refused write
 * is re-read and decided again.
 */
class ReportPointer(
    private val store: ConditionalObjectStore,
) {
    /**
     * Writes the "No report yet" pointer into [folder] only if no pointer is there.
     *
     * @return whether it was written; false leaves an existing pointer exactly as it is.
     */
    fun ensurePlaceholder(folder: ClusterS3Path): Boolean =
        store.putIfAbsent(placeholder().toByteArray(), pointerOf(folder), CONTENT_TYPE) == ConditionalObjectStore.Written

    /**
     * Points [folder]'s pointer at the upload [stamp], whose front page is [frontPage], unless it
     * already names an equal or newer upload.
     *
     * @return whether the pointer now leads to this upload.
     * @throws IllegalStateException if other writers changed the pointer between every read and
     *   write, [Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS] times.
     */
    fun advance(
        folder: ClusterS3Path,
        stamp: ReportStamp,
        frontPage: String,
    ): Boolean {
        val path = pointerOf(folder)
        val content = render(stamp, frontPage).toByteArray()
        repeat(Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
            val current = store.readVersioned(path)
            val shown = current?.let { target(it.content) }
            if (shown != null && shown >= stamp) return false
            val written =
                if (current == null) {
                    store.putIfAbsent(content, path, CONTENT_TYPE) == ConditionalObjectStore.Written
                } else {
                    store.putIfMatch(content, path, current.etag, CONTENT_TYPE) == ConditionalObjectStore.Written
                }
            if (written) return true
        }
        error(
            "The report pointer ${path.toUri()} changed under every one of " +
                "${Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS} writes; the upload is stored, run report upload again to point at it.",
        )
    }

    /** Renders pointers and reads them back. */
    companion object {
        private const val CONTENT_TYPE = "text/html; charset=utf-8"
        private const val TITLE = "Report"
        private const val MARKER = "easy-db-lab report pointer:"
        private val MARKER_LINE = Regex("<!-- $MARKER (\\d{8}-\\d{6}) -->")

        /** The pointer to the upload [stamp], leading to its [frontPage]. */
        fun render(
            stamp: ReportStamp,
            frontPage: String,
        ): String {
            val target = ReportHtml.escape("${stamp.folderName}/$frontPage")
            return ReportHtml
                .page(TITLE, "<p><a href=\"$target\">The newest report, uploaded ${stamp.timestamp} UTC</a></p>")
                .replaceFirst("<head>", "<head>\n<!-- $MARKER ${stamp.timestamp} -->\n<meta http-equiv=\"refresh\" content=\"0; url=$target\">")
        }

        /** The upload the pointer [content] leads to, or null for the placeholder or any page this class did not write. */
        fun target(content: String): ReportStamp? = MARKER_LINE.find(content)?.let { ReportStamp(it.groupValues[1]) }

        private fun placeholder(): String = ReportHtml.page(TITLE, "<p>${Constants.Observability.REPORT_PLACEHOLDER_TEXT}</p>")

        private fun pointerOf(folder: ClusterS3Path): ClusterS3Path = folder.resolve(Constants.Observability.REPORT_POINTER)
    }
}
