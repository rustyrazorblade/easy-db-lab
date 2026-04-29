package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.ClickHouseBackupService
import org.koin.core.component.inject
import picocli.CommandLine.Command

@McpCommand
@RequireProfileSetup
@Command(
    name = "list-backups",
    description = ["List available ClickHouse backups in the account S3 bucket"],
)
class ClickHouseListBackups : PicoBaseCommand() {
    private val clickHouseBackupService: ClickHouseBackupService by inject()

    override fun execute() {
        val accountBucket =
            requireNotNull(clusterState.s3Bucket) {
                "Account bucket not configured. Run 'easy-db-lab up' first."
            }
        val backups = clickHouseBackupService.listBackups(accountBucket).getOrThrow()

        if (backups.isEmpty()) {
            eventBus.emit(Event.ClickHouse.Backup.BackupListEmpty)
            return
        }

        eventBus.emit(Event.ClickHouse.Backup.BackupListHeader)
        for (backup in backups) {
            eventBus.emit(
                Event.ClickHouse.Backup.BackupListEntry(
                    name = backup.backupName,
                    timestamp = backup.timestamp,
                    sourceCluster = backup.sourceCluster,
                    sizeBytes = backup.totalSizeBytes,
                ),
            )
        }
    }
}
