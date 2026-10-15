package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * The pointer of an experiment's report folder, `<folder>/index.html`: the page the documents
 * panels show, which leads to the newest upload's front page.
 *
 * The pointer is derived, and it is the only object under the folder that is ever rewritten. It
 * only moves forward: it names its upload's [ReportStamp] in a comment line, and an upload leaves a
 * pointer that names an equal or newer one. A pointer that names this upload's own stamp always
 * counts as written: a stamp belongs to the one upload that holds its zip key, so no other writer
 * writes it. Every write is conditional on the version that was read, so of two uploads that
 * finish together the newer wins whatever their order. A refused write is re-read and decided
 * again; when the re-read still shows the version the write was refused on, a write is in flight
 * there, and the pointer waits a bounded number of looks for it to land before deciding.
 */
class ReportPointer(
    private val store: ConditionalObjectStore,
    private val inFlightLookInterval: Duration = Duration.ofMillis(Constants.Observability.REPORT_IN_FLIGHT_LOOK_INTERVAL_MS),
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
     * @return whether the pointer now leads to this upload: true when it names [stamp], false when
     *   it names a newer upload.
     * @throws IllegalStateException if the pointer still named an older upload after
     *   [Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS] refused writes and one final re-read.
     */
    fun advance(
        folder: ClusterS3Path,
        stamp: ReportStamp,
        frontPage: String,
    ): Boolean {
        val path = pointerOf(folder)
        val content = render(stamp, frontPage).toByteArray()
        var current = store.readVersioned(path)
        var waitsGivenUp = 0
        repeat(Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS) {
            decided(current, stamp)?.let { return it }
            if (write(path, content, current)) return true
            val reread = reread(path, current)
            if (reread?.etag == current?.etag) waitsGivenUp++
            current = reread
        }
        // The last re-read is the final one: it may show this upload's write, whose response was
        // lost, or a newer upload's pointer.
        decided(current, stamp)?.let { return it }
        val waits = if (waitsGivenUp == 0) "no wait" else "$waitsGivenUp waits"
        error(
            "The report pointer ${path.toUri()} led to neither this upload nor a newer one after " +
                "${Constants.Observability.REPORT_POINTER_MAX_ATTEMPTS} refused writes; $waits for a write in flight gave up. " +
                "The upload is stored, run report upload again to point at it.",
        )
    }

    /** Writes [content] to [path] on the version [current] was read at; whether it was written. */
    private fun write(
        path: ClusterS3Path,
        content: ByteArray,
        current: ConditionalObjectStore.Versioned?,
    ): Boolean =
        if (current == null) {
            store.putIfAbsent(content, path, CONTENT_TYPE) == ConditionalObjectStore.Written
        } else {
            store.putIfMatch(content, path, current.etag, CONTENT_TYPE) == ConditionalObjectStore.Written
        }

    /**
     * Re-reads [path] after a write refused on the version [refusedOn]. A re-read that still shows
     * that version means a write is in flight there, so it looks again, a bounded number of times,
     * until the pointer changes; any S3 error fails at once. Returns the last read.
     */
    private fun reread(
        path: ClusterS3Path,
        refusedOn: ConditionalObjectStore.Versioned?,
    ): ConditionalObjectStore.Versioned? {
        var looks = 0
        val last =
            pollUntil(
                "report-pointer-write-in-flight",
                Constants.Observability.REPORT_IN_FLIGHT_MAX_LOOKS,
                inFlightLookInterval,
                transient = { false },
                done = { it?.etag != refusedOn?.etag },
            ) {
                looks++
                store.readVersioned(path)
            }
        val from = refusedOn?.etag ?: "none"
        when {
            looks == 1 -> Unit
            last?.etag != refusedOn?.etag ->
                log.info { "Pointer ${path.toUri()}: write in flight settled after $looks looks, etag $from -> ${last?.etag ?: "none"}" }
            else -> log.warn { "Pointer ${path.toUri()}: wait for a write in flight gave up after $looks looks, etag still $from" }
        }
        return last
    }

    /**
     * What the pointer [current] settles for the upload [stamp]: true when it names [stamp], false
     * when it names a newer upload, and null when this upload must still write it.
     */
    private fun decided(
        current: ConditionalObjectStore.Versioned?,
        stamp: ReportStamp,
    ): Boolean? {
        val shown = current?.let { target(it.content) }
        return when {
            shown == stamp -> true
            shown != null && shown > stamp -> false
            else -> null
        }
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
