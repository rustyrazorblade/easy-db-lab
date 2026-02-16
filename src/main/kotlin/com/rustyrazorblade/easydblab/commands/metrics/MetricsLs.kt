package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.s3Path
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
            outputHandler.handleMessage("No VictoriaMetrics backups found.")
            return
        }

        // Group files by the first path segment after victoriametrics/
        val vmPrefix = clusterState.s3Path().victoriaMetrics().getKey()
        val grouped =
            files.groupBy { fileInfo ->
                val relativePath = fileInfo.path.getKey().removePrefix("$vmPrefix/")
                relativePath.substringBefore("/")
            }

        outputHandler.handleMessage("VictoriaMetrics backups:")
        outputHandler.handleMessage("")
        outputHandler.handleMessage("%-30s  %10s  %15s".format("Timestamp", "Files", "Total Size"))
        outputHandler.handleMessage("-".repeat(TABLE_SEPARATOR_LENGTH))

        for ((timestamp, groupFiles) in grouped.toSortedMap()) {
            val totalSize = groupFiles.sumOf { it.size }
            outputHandler.handleMessage(
                "%-30s  %10d  %15s".format(timestamp, groupFiles.size, formatSize(totalSize)),
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < BYTES_PER_KB) return "$bytes B"
        if (bytes < BYTES_PER_MB) return "%.1f KB".format(bytes.toDouble() / BYTES_PER_KB)
        if (bytes < BYTES_PER_GB) return "%.1f MB".format(bytes.toDouble() / BYTES_PER_MB)
        return "%.1f GB".format(bytes.toDouble() / BYTES_PER_GB)
    }

    companion object {
        private const val TABLE_SEPARATOR_LENGTH = 59
        private const val BYTES_PER_KB = 1024L
        private const val BYTES_PER_MB = 1024L * 1024
        private const val BYTES_PER_GB = 1024L * 1024 * 1024
    }
}
