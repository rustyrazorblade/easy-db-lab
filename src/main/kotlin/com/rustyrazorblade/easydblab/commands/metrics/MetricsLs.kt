package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * List VictoriaMetrics backups stored in S3.
 *
 * Displays a summary of available backups grouped by timestamp directory,
 * showing file count and total size for each backup.
 *
 * Example:
 * ```
 * easy-db-lab metrics ls
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "ls",
    description = ["List VictoriaMetrics backups in S3"],
)
class MetricsLs : PicoBaseCommand() {
    private val objectStore: ObjectStore by inject()

    override fun execute() {
        val files = objectStore.listFiles(clusterState.s3Path().victoriaMetrics(), recursive = true)

        if (files.isEmpty()) {
            eventBus.emit(Event.Metrics.BackupListEmpty)
            return
        }

        // Group files by the first path segment after victoriametrics/
        val vmPrefix = clusterState.s3Path().victoriaMetrics().getKey()
        val grouped =
            files.groupBy { fileInfo ->
                val relativePath = fileInfo.path.getKey().removePrefix("$vmPrefix/")
                relativePath.substringBefore("/")
            }

        val entries =
            grouped.toSortedMap().map { (timestamp, groupFiles) ->
                val totalSize = groupFiles.sumOf { it.size }
                Event.Metrics.BackupEntry(timestamp, groupFiles.size, formatSize(totalSize))
            }
        eventBus.emit(Event.Metrics.BackupList(entries))
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < BYTES_PER_KB) return "$bytes B"
        if (bytes < BYTES_PER_MB) return "%.1f KB".format(bytes.toDouble() / BYTES_PER_KB)
        if (bytes < BYTES_PER_GB) return "%.1f MB".format(bytes.toDouble() / BYTES_PER_MB)
        return "%.1f GB".format(bytes.toDouble() / BYTES_PER_GB)
    }

    companion object {
        private const val BYTES_PER_KB = 1024L
        private const val BYTES_PER_MB = 1024L * 1024
        private const val BYTES_PER_GB = 1024L * 1024 * 1024
    }
}
