package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The pointer of an experiment's report folder, `<folder>/index.html`: the page the documents
 * panels show, which leads to the newest upload's front page.
 *
 * The pointer is derived, and it is the only object under the folder that is ever rewritten. It
 * only moves forward: it names its upload's [ReportStamp] in a comment line, and an upload leaves a
 * pointer that names an equal or newer one. Every write is conditional on the version that was
 * read, so of two uploads that finish together the newer wins whatever their order; a refused write
 * is re-read and decided again. Only a write refused on a retried attempt whose re-read already
 * names this upload counts as written (an earlier attempt committed and lost its response); after
 * a first-attempt refusal, a pointer naming this upload's second is another writer's.
 */
class ReportPointer(
    private val store: ConditionalObjectStore,
) {
    /**
     * Writes the "No report yet" pointer into [folder] only if no pointer is there; an existing
     * pointer is left exactly as it is. Either way the folder has a pointer afterwards, so the
     * outcome of the write is not reported.
     */
    fun ensurePlaceholder(folder: ClusterS3Path) {
        store.putIfAbsent(placeholder().toByteArray(), pointerOf(folder), CONTENT_TYPE)
    }

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
        var refusedAfterRetry = false
        repeat(Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
            val current = store.readVersioned(path)
            val shown = current?.let { target(it.content) }
            // A write refused on a retried attempt that already shows this upload is this writer's
            // own: an earlier attempt committed and its response was lost.
            if (refusedAfterRetry && acceptedAsOwn(path, shown, stamp)) return true
            if (shown != null && shown >= stamp) return false
            val result =
                if (current == null) {
                    store.putIfAbsent(content, path, CONTENT_TYPE)
                } else {
                    store.putIfMatch(content, path, current.etag, CONTENT_TYPE)
                }
            if (result == ConditionalObjectStore.Written) return true
            refusedAfterRetry = result == ConditionalObjectStore.RefusedAfterRetry
        }
        error(
            "The report pointer ${path.toUri()} changed under every one of " +
                "${Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS} writes; the upload is stored, run report upload again to point at it.",
        )
    }

    /** Whether the pointer at [path], re-read after a write refused on a retry and naming [shown], is this upload's [stamp]. */
    private fun acceptedAsOwn(
        path: ClusterS3Path,
        shown: ReportStamp?,
        stamp: ReportStamp,
    ): Boolean {
        val accepted = shown == stamp
        log.info {
            "Pointer write to ${path.toUri()} refused after a retry; it names ${shown?.timestamp ?: "no upload"}, accepted=$accepted"
        }
        return accepted
    }

    /** Renders pointers and reads them back. */
    companion object {
        private val log = KotlinLogging.logger {}
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
                .replaceFirst(
                    "<head>",
                    "<head>\n<!-- $MARKER ${stamp.timestamp} -->\n<meta http-equiv=\"refresh\" content=\"0; url=$target\">",
                )
        }

        /** The upload the pointer [content] leads to, or null for the placeholder or any page this class did not write. */
        fun target(content: String): ReportStamp? = MARKER_LINE.find(content)?.let { ReportStamp(it.groupValues[1]) }

        private fun placeholder(): String = ReportHtml.page(TITLE, "<p>${Constants.Observability.REPORT_PLACEHOLDER_TEXT}</p>")

        private fun pointerOf(folder: ClusterS3Path): ClusterS3Path = folder.resolve(Constants.Observability.REPORT_POINTER)
    }
}
