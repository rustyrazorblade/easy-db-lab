package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.clickhouseBackupsRoot
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class BackupMetadata(
    val backupName: String,
    val timestamp: String,
    val sourceCluster: String,
    val totalSizeBytes: Long,
)

data class BackupOperation(
    val name: String,
    val status: String,
    val startTime: String,
    val bytesRead: Long,
    val totalSize: Long,
    val error: String,
)

interface ClickHouseBackupService {
    fun backup(
        controlHost: ClusterHost,
        backupName: String,
        accountBucket: String,
        clusterName: String,
        async: Boolean = false,
    ): Result<Unit>

    fun restore(
        controlHost: ClusterHost,
        backupName: String,
        accountBucket: String,
        async: Boolean = false,
    ): Result<Unit>

    fun listBackups(accountBucket: String): Result<List<BackupMetadata>>

    fun getBackupOperations(controlHost: ClusterHost): Result<List<BackupOperation>>
}

class DefaultClickHouseBackupService(
    private val podOps: K8sPodOperations,
    private val objectStore: ObjectStore,
    private val eventBus: EventBus,
) : ClickHouseBackupService {
    private val log = KotlinLogging.logger {}

    override fun backup(
        controlHost: ClusterHost,
        backupName: String,
        accountBucket: String,
        clusterName: String,
        async: Boolean,
    ): Result<Unit> =
        runCatching {
            val backupsRoot = clickhouseBackupsRoot(accountBucket)
            val backupPath = backupsRoot.resolve(backupName)
            val metadataPath = backupPath.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)

            if (objectStore.fileExists(metadataPath)) {
                eventBus.emit(Event.ClickHouse.Backup.BackupAlreadyExists(backupName))
                error("Backup '$backupName' already exists at ${backupPath.toUri()}")
            }

            eventBus.emit(Event.ClickHouse.Backup.BackupStarting(backupName))

            val asyncKeyword = if (async) "ASYNC " else ""
            val query =
                "BACKUP ${asyncKeyword}DATABASE default ON CLUSTER ${Constants.ClickHouse.CLUSTER_NAME} TO Disk('s3_backup', '$backupName/')"
            podOps
                .execInPod(
                    controlHost = controlHost,
                    namespace = Constants.ClickHouse.NAMESPACE,
                    podName = Constants.ClickHouse.PRIMARY_POD_NAME,
                    command = listOf("clickhouse-client", "--password", Constants.ClickHouse.DEFAULT_PASSWORD, "--query", query),
                ).getOrThrow()

            if (async) {
                eventBus.emit(Event.ClickHouse.Backup.BackupStartedAsync(backupName))
                return@runCatching
            }

            val sizeBytes = objectStore.listFiles(backupPath).sumOf { it.size }
            val metadata =
                BackupMetadata(
                    backupName = backupName,
                    timestamp = Instant.now().toString(),
                    sourceCluster = clusterName,
                    totalSizeBytes = sizeBytes,
                )
            val metadataContent = Json.encodeToString(metadata)
            objectStore.uploadContent(metadataContent, metadataPath)

            log.info { "Backup '$backupName' written to ${backupPath.toUri()}" }
            eventBus.emit(Event.ClickHouse.Backup.BackupComplete(backupName, backupPath.toUri()))
        }

    override fun restore(
        controlHost: ClusterHost,
        backupName: String,
        accountBucket: String,
        async: Boolean,
    ): Result<Unit> =
        runCatching {
            val backupsRoot = clickhouseBackupsRoot(accountBucket)
            val backupPath = backupsRoot.resolve(backupName)
            val metadataPath = backupPath.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)

            if (!objectStore.fileExists(metadataPath)) {
                eventBus.emit(Event.ClickHouse.Backup.BackupNotFound(backupName))
                error("Backup '$backupName' not found in $accountBucket")
            }

            eventBus.emit(Event.ClickHouse.Backup.RestoreStarting(backupName))

            val asyncKeyword = if (async) "ASYNC " else ""
            val query =
                "RESTORE ${asyncKeyword}DATABASE default ON CLUSTER ${Constants.ClickHouse.CLUSTER_NAME} FROM Disk('s3_backup', '$backupName/')"
            podOps
                .execInPod(
                    controlHost = controlHost,
                    namespace = Constants.ClickHouse.NAMESPACE,
                    podName = Constants.ClickHouse.PRIMARY_POD_NAME,
                    command = listOf("clickhouse-client", "--password", Constants.ClickHouse.DEFAULT_PASSWORD, "--query", query),
                ).getOrThrow()

            if (async) {
                eventBus.emit(Event.ClickHouse.Backup.RestoreStartedAsync(backupName))
                return@runCatching
            }

            eventBus.emit(Event.ClickHouse.Backup.RestoreComplete(backupName))
        }

    override fun listBackups(accountBucket: String): Result<List<BackupMetadata>> =
        runCatching {
            val backupsRoot = clickhouseBackupsRoot(accountBucket)
            objectStore
                .listFiles(backupsRoot, recursive = false)
                .mapNotNull { dir ->
                    val metadataPath = dir.path.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)
                    runCatching {
                        Json.decodeFromString<BackupMetadata>(objectStore.readContent(metadataPath))
                    }.onFailure { e ->
                        log.warn { "Failed to parse backup metadata at $metadataPath: ${e.message}" }
                    }.getOrNull()
                }.sortedByDescending { it.timestamp }
        }

    override fun getBackupOperations(controlHost: ClusterHost): Result<List<BackupOperation>> =
        runCatching {
            val query =
                "SELECT name, status, start_time, bytes_read, total_size, error FROM system.backups ORDER BY start_time DESC LIMIT 20 FORMAT TabSeparated"
            val output =
                podOps
                    .execInPod(
                        controlHost = controlHost,
                        namespace = Constants.ClickHouse.NAMESPACE,
                        podName = Constants.ClickHouse.PRIMARY_POD_NAME,
                        command = listOf("clickhouse-client", "--password", Constants.ClickHouse.DEFAULT_PASSWORD, "--query", query),
                    ).getOrThrow()

            output
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 6) {
                        log.warn { "Unexpected format in system.backups row: $line" }
                        return@mapNotNull null
                    }
                    BackupOperation(
                        name = parts[0],
                        status = parts[1],
                        startTime = parts[2],
                        bytesRead = parts[3].toLongOrNull() ?: 0L,
                        totalSize = parts[4].toLongOrNull() ?: 0L,
                        error = parts[5],
                    )
                }
        }
}
