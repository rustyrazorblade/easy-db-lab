package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
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
 * pointer is not complete. Running `report upload` again stores a new upload and points at it;
 * this one stays stored and is not pointed at. The message gives that advice once, here.
 */
class ReportUploadIncompleteException(
    val zip: ClusterS3Path,
    cause: Throwable,
) : IllegalStateException(
        "The report zip is stored at ${zip.toUri()}, but unpacking it or moving the report pointer failed: " +
            "${cause.message?.removeSuffix(".")}. Running report upload again stores and points at a new upload.",
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
     * is built once and those exact bytes are stored. A refused write is decided by what its key
     * holds ([ImmutableObjectWriter]): exactly this upload's bytes are its own, so the zip keeps its
     * second; other bytes move the zip to the next second and fail an unpacked file; a key still
     * empty after every look moves the zip on too, and fails an unpacked file.
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
 * Default [ReportArchiveService]: [ReportZip] builds, [ImmutableObjectWriter] stores each object,
 * [ReportUnpacker] unpacks, [ReportPointer] moves the pointer. Every object is written with a
 * conditional write that never replaces one; only the pointer is ever replaced, and only by a newer
 * upload.
 */
class DefaultReportArchiveService(
    store: ConditionalObjectStore,
    private val clock: Clock = Clock.systemUTC(),
    inFlightLookInterval: Duration = Duration.ofMillis(Constants.Observability.REPORT_IN_FLIGHT_LOOK_INTERVAL_MS),
    private val uploadIds: () -> String = { UUID.randomUUID().toString() },
) : ReportArchiveService {
    private val writer = ImmutableObjectWriter(store, inFlightLookInterval)
    private val pointer = ReportPointer(store, inFlightLookInterval)

    override fun upload(
        clusterState: ClusterState,
        dir: File,
    ): UploadedReport {
        val archive = ReportZip.build(dir, uploadIds())
        val folder = ObservabilityStore.from(clusterState).documentsRoot(ExperimentKey.of(clusterState))
        val stamp = storeZip(folder, archive)
        val zip = folder.resolve(stamp.zipName)
        val unpacked = folder.resolve(stamp.folderName)
        // An interrupt is the caller's to handle, and an Error is not an incomplete upload: only
        // an ordinary failure becomes one.
        val advanced =
            try {
                val report = ReportUnpacker.unpack(archive.bytes)
                report.objects.forEach { put(unpacked.resolve(it.path), it.content, it.contentType) }
                pointer.advance(folder, stamp, report.frontPage)
            } catch (e: Exception) {
                throw (e as? InterruptedException) ?: ReportUploadIncompleteException(zip, e)
            }
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
        val unclaimed = mutableListOf<KeyHolder>()
        candidates.forEach { stamp ->
            val holder = zipHolderAt(folder.resolve(stamp.zipName), archive.bytes)
            if (holder == KeyHolder.OWN) return stamp
            unclaimed += holder
        }
        error(
            "No report name for ${candidates.size} seconds up to ${candidates.last().zipName} could be claimed in " +
                "${folder.toUri()}: ${unclaimed.count { it == KeyHolder.OTHER }} held by other uploads, " +
                "${unclaimed.count { it == KeyHolder.EMPTY }} still empty after a write in flight; nothing was uploaded.",
        )
    }

    /** Whose object [path] holds once this upload's zip [bytes] were written; any holder but [KeyHolder.OWN] moves to the next second. */
    private fun zipHolderAt(
        path: ClusterS3Path,
        bytes: ByteArray,
    ): KeyHolder {
        val decision = writer.write(bytes, path, ZIP_CONTENT_TYPE)
        when (decision.holder) {
            KeyHolder.OWN, KeyHolder.OTHER -> logRefused(path, decision)
            KeyHolder.EMPTY ->
                log.warn {
                    "Zip ${path.toUri()} refused and still empty after ${decision.looks} looks; moving to the next second. " +
                        "The abandoned key may still receive a write in flight."
                }
        }
        return decision.holder
    }

    private fun put(
        path: ClusterS3Path,
        content: ByteArray,
        contentType: String,
    ) {
        val decision = writer.write(content, path, contentType)
        when (decision.holder) {
            KeyHolder.OWN -> logRefused(path, decision)
            KeyHolder.OTHER -> {
                logRefused(path, decision)
                error("${path.toUri()}: another object holds that key; an upload never replaces an object, so it was left unchanged.")
            }
            KeyHolder.EMPTY -> {
                log.warn { "Write to ${path.toUri()} refused and still empty after ${decision.looks} looks; failing the upload." }
                error("${path.toUri()} was refused and the key was still empty after ${decision.looks} looks.")
            }
        }
    }

    /** Logs how a refused write to [path] was decided; a write that landed at once is not logged here. */
    private fun logRefused(
        path: ClusterS3Path,
        decision: WriteDecision,
    ) {
        if (decision.looks > 0) {
            val how = if (decision.rewritten) "rewritten" else "read back"
            log.info { "Write to ${path.toUri()} refused; decided: ${decision.holder.name.lowercase()} ($how), looks=${decision.looks}" }
        }
    }

    private companion object {
        private const val ZIP_CONTENT_TYPE = "application/zip"
        private val log = KotlinLogging.logger {}
    }
}
