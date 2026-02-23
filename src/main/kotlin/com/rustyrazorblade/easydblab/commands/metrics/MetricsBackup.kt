package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.VictoriaBackupService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Backup VictoriaMetrics data to S3.
 *
 * This command creates a backup of VictoriaMetrics data using the native vmbackup tool.
 * The backup is stored in S3 at: s3://{bucket}/victoriametrics/{timestamp}/
 *
 * The backup is non-disruptive - metrics collection continues during the backup process.
 *
 * Example:
 * ```
 * easy-db-lab metrics backup
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "backup",
    description = ["Backup VictoriaMetrics data to S3"],
)
class MetricsBackup : PicoBaseCommand() {
    private val victoriaBackupService: VictoriaBackupService by inject()

    @Option(names = ["--dest"], description = ["Destination S3 URI (default: cluster S3 bucket)"])
    var dest: String? = null

    override fun execute() {
        val controlHost = clusterState.getControlHost()
        if (controlHost == null) {
            eventBus.emit(Event.Error("No control node found. Please ensure the cluster is running."))
            return
        }

        victoriaBackupService
            .backupMetrics(controlHost, clusterState, dest)
            .onSuccess { result ->
                eventBus.emit(Event.Message("VictoriaMetrics backup completed successfully"))
                eventBus.emit(Event.Message("Backup location: ${result.s3Path.toUri()}"))
            }.onFailure { exception ->
                eventBus.emit(Event.Error("VictoriaMetrics backup failed: ${exception.message}"))
            }
    }
}
