package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import java.io.File
import java.time.Clock

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
     * upload is never shared, even by an upload of the same unchanged directory. A write refused on
     * a retried attempt of its own request whose object holds exactly this upload's bytes is this
     * upload's own (an earlier attempt committed and lost its response), so it is neither stored
     * again nor treated as a conflict.
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
) : ReportArchiveService {
    private val pointer = ReportPointer(store)

    override fun upload(
        clusterState: ClusterState,
        dir: File,
    ): UploadedReport {
        val archive = ReportZip.build(dir)
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
     * the object read back and accepted when it has exactly these bytes. A write refused on its
     * first attempt found another upload's object there, even one with the same bytes.
     */
    private fun storedAsOwn(
        content: ByteArray,
        path: ClusterS3Path,
        contentType: String,
    ): Boolean =
        when (store.putIfAbsent(content, path, contentType)) {
            ConditionalObjectStore.Written -> true
            ConditionalObjectStore.AlreadyExists -> false
            ConditionalObjectStore.RefusedAfterRetry -> store.readBytes(path)?.contentEquals(content) == true
        }

    private companion object {
        private const val ZIP_CONTENT_TYPE = "application/zip"
    }
}
