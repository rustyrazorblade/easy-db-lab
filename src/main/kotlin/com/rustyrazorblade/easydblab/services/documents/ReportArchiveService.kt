package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * One stored upload.
 *
 * @property zip The zip, stored exactly as it was built.
 * @property unpacked The folder the zip was unpacked to, beside it.
 * @property pointerAdvanced Whether the experiment's pointer now leads to this upload; false when
 *   it already led to an upload of the same or a later second.
 * @property files The directory's files, relative to it, in path order.
 */
data class UploadedReport(
    val zip: ClusterS3Path,
    val unpacked: ClusterS3Path,
    val pointerAdvanced: Boolean,
    val files: List<String>,
)

/**
 * An upload stopped after its zip was stored: the zip at [zip] is kept, but its folder or the
 * pointer is not complete. Running `report upload` again stores a new upload.
 */
class ReportUploadIncompleteException(
    val zip: ClusterS3Path,
    cause: Throwable,
) : IllegalStateException(
        "The report zip is stored at ${zip.toUri()}, but unpacking it or moving the report pointer failed: " +
            "${cause.message}. Run report upload again.",
        cause,
    )

/**
 * An experiment's reports: each upload of a directory stored as one zip under
 * `reports/<tenant>/<experiment>/`, kept forever, unpacked beside it, with the pointer leading to
 * the newest.
 *
 * It uses the operator's own credentials and needs only the cluster state, so it works before and
 * after `down`.
 */
interface ReportArchiveService {
    /**
     * Checks [dir], zips it, and stores the zip as `report-<yyyyMMdd-HHmmss>.zip` (UTC) in the
     * experiment's folder of [clusterState], only where no object is, moving to the next free
     * second when that second is taken. Then unpacks the same zip to `report-<ts>/` and moves the
     * pointer forward to it. Nothing earlier is replaced or removed, and a second taken by another
     * upload is never shared, even by an upload of the same unchanged directory: every upload's zip
     * carries its own random id in the zip comment, so no other upload's zip has its bytes. The zip
     * is built once and those exact bytes are stored. A write refused on a retried attempt of its
     * own request whose object holds exactly this upload's bytes is this upload's own (an earlier
     * attempt committed and lost its response), so it is neither stored again nor treated as a
     * conflict. When that retried attempt met a write still in flight at the key, which may be
     * this upload's own earlier attempt, the key is looked at again until it holds an object before
     * it is decided, so the earlier attempt never lands later as an orphan zip.
     *
     * @throws ReportRejectedException naming every offender, before anything is stored, if [dir]
     *   breaks the report rules.
     * @throws ReportUploadIncompleteException if the zip was stored but a later step failed.
     */
    fun upload(
        clusterState: ClusterState,
        dir: File,
    ): UploadedReport
}

/**
 * Default [ReportArchiveService]: [ReportZip] builds, [ReportUnpacker] unpacks, [ReportPointer]
 * moves the pointer. Every object is written with a conditional write that never replaces one;
 * only the pointer is ever replaced, and only by a newer upload.
 */
class DefaultReportArchiveService(
    private val store: ConditionalObjectStore,
    private val clock: Clock = Clock.systemUTC(),
    private val inFlightLookInterval: Duration = Duration.ofMillis(Constants.Observability.REPORT_IN_FLIGHT_LOOK_INTERVAL_MS),
    private val uploadIds: () -> String = { UUID.randomUUID().toString() },
) : ReportArchiveService {
    private val pointer = ReportPointer(store)

    override fun upload(
        clusterState: ClusterState,
        dir: File,
    ): UploadedReport {
        val archive = ReportZip.build(dir, uploadIds())
        val folder = ObservabilityStore.from(clusterState).documentsRoot(ExperimentKey.of(clusterState))
        val stamp = storeZip(folder, archive)
        val zip = folder.resolve(stamp.zipName)
        val unpacked = folder.resolve(stamp.folderName)
        val advanced =
            runCatching {
                val report = ReportUnpacker.unpack(archive.bytes)
                report.objects.forEach { put(unpacked.resolve(it.path), it.content, it.contentType) }
                pointer.advance(folder, stamp, report.frontPage)
            }.getOrElse { throw ReportUploadIncompleteException(zip, it) }
        return UploadedReport(zip, unpacked, advanced, archive.files)
    }

    private fun storeZip(
        folder: ClusterS3Path,
        archive: ReportArchive,
    ): ReportStamp {
        val candidates =
            generateSequence(ReportStamp.of(clock.instant())) { it.next() }
                .take(Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS)
                .toList()
        return candidates.firstOrNull { stamp ->
            storedAsOwn(archive.bytes, folder.resolve(stamp.zipName), ZIP_CONTENT_TYPE)
        } ?: error(
            "Every report name for ${Constants.Observability.REPORT_ZIP_KEY_MAX_ATTEMPTS} seconds up to " +
                "${candidates.last().zipName} is taken in ${folder.toUri()}; nothing was uploaded.",
        )
    }

    private fun put(
        path: ClusterS3Path,
        content: ByteArray,
        contentType: String,
    ) {
        check(storedAsOwn(content, path, contentType)) {
            "${path.toUri()} already exists; an upload never replaces an object, so it was left unchanged."
        }
    }

    /**
     * Writes [content] to [path] only where no object is, and tells whether [path] now holds this
     * writer's [content]. Only a write refused on a retried attempt of this same request may be
     * this writer's own (an earlier attempt committed and lost only its response), so only then is
     * the object read back and accepted when it has exactly these bytes. That is sound because no
     * other upload writes these bytes there: each zip is unique to its upload (its comment holds
     * the upload id), and an unpacked file lands under the folder of the second whose zip this
     * upload holds. A write refused on its first attempt found another upload's object there, even
     * one with the same bytes.
     */
    private fun storedAsOwn(
        content: ByteArray,
        path: ClusterS3Path,
        contentType: String,
    ): Boolean =
        when (store.putIfAbsent(content, path, contentType)) {
            ConditionalObjectStore.Written -> true
            ConditionalObjectStore.AlreadyExists -> false
            ConditionalObjectStore.RefusedAfterRetry -> {
                val accepted = store.readBytes(path)?.contentEquals(content) == true
                log.info { "Write to ${path.toUri()} refused after a retry; read back, accepted=$accepted" }
                accepted
            }
            ConditionalObjectStore.ConflictAfterRetry -> settledAsOwn(content, path, contentType)
        }

    /**
     * Decides a write to [path] refused after a retry because a conditional write was still in
     * flight there. That write may be this writer's own earlier attempt, not yet committed, so an
     * empty key is not given up at once: each look reads [path] back and, while it holds nothing,
     * writes [content] there again, until the key holds an object (accepted when it has exactly
     * these bytes) or this writer's write lands. A key still empty after every look is given up.
     */
    private fun settledAsOwn(
        content: ByteArray,
        path: ClusterS3Path,
        contentType: String,
    ): Boolean {
        val accepted =
            pollUntil<Boolean?>(
                "report-write-in-flight",
                Constants.Observability.REPORT_IN_FLIGHT_MAX_LOOKS,
                inFlightLookInterval,
                done = { it != null },
            ) {
                store.readBytes(path)?.contentEquals(content)
                    ?: true.takeIf { store.putIfAbsent(content, path, contentType) == ConditionalObjectStore.Written }
            } == true
        log.info { "Write to ${path.toUri()} met a write in flight after a retry; looked again, accepted=$accepted" }
        return accepted
    }

    private companion object {
        private const val ZIP_CONTENT_TYPE = "application/zip"
        private val log = KotlinLogging.logger {}
    }
}
