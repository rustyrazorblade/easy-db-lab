package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The UTC second of one upload, which names its zip and its folder.
 *
 * The `yyyyMMdd-HHmmss` form sorts as time does, so comparing two stamps compares the uploads.
 *
 * @property timestamp The second, `yyyyMMdd-HHmmss`, in UTC.
 */
data class ReportStamp(
    val timestamp: String,
) : Comparable<ReportStamp> {
    init {
        require(PATTERN.matches(timestamp)) { "Invalid report timestamp '$timestamp': expected yyyyMMdd-HHmmss" }
    }

    /** The upload's folder, `report-<timestamp>`. */
    val folderName: String get() = Constants.Observability.REPORT_UPLOAD_PREFIX + timestamp

    /** The upload's zip, `report-<timestamp>.zip`. */
    val zipName: String get() = "$folderName.zip"

    /** The stamp of the following second. */
    fun next(): ReportStamp = of(LocalDateTime.parse(timestamp, FORMAT).toInstant(ZoneOffset.UTC).plusSeconds(1))

    override fun compareTo(other: ReportStamp): Int = timestamp.compareTo(other.timestamp)

    /** Builds stamps from instants. */
    companion object {
        private val PATTERN = Regex("\\d{8}-\\d{6}")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** The stamp of the second [at] falls in, in UTC. */
        fun of(at: Instant): ReportStamp = ReportStamp(FORMAT.format(at.atOffset(ZoneOffset.UTC)))
    }
}
