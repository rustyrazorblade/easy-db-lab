package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.clickhouseBackupsRoot
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ClickHouseBackupServiceTest : BaseKoinTest() {
    private val mockPodOps: K8sPodOperations = mock()
    private val mockObjectStore: ObjectStore = mock()
    private val mockEventBus: EventBus = mock()

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val accountBucket = "easy-db-lab-account-bucket"
    private val clusterName = "test-cluster"
    private val backupName = "my-backup"

    private lateinit var service: ClickHouseBackupService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sPodOperations> { mockPodOps }
                single<ObjectStore> { mockObjectStore }
                single<EventBus> { mockEventBus }
                factory<ClickHouseBackupService> {
                    DefaultClickHouseBackupService(get(), get(), get())
                }
            },
        )

    @BeforeEach
    fun setup() {
        reset(mockPodOps, mockObjectStore, mockEventBus)
        service = getKoin().get()
    }

    @Test
    fun `backup fails when backup already exists`() {
        val metadataPath =
            clickhouseBackupsRoot(accountBucket)
                .resolve(backupName)
                .resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)
        whenever(mockObjectStore.fileExists(metadataPath)).thenReturn(true)

        val result = service.backup(testControlHost, backupName, accountBucket, clusterName)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("already exists")
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.BackupAlreadyExists })
        verify(mockPodOps, never()).execInPod(any(), any(), any(), any())
    }

    @Test
    fun `backup succeeds when backup does not exist`() {
        val backupsRoot = clickhouseBackupsRoot(accountBucket)
        val backupPath = backupsRoot.resolve(backupName)
        val metadataPath = backupPath.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)

        whenever(mockObjectStore.fileExists(metadataPath)).thenReturn(false)
        whenever(mockPodOps.execInPod(any(), any(), any(), any()))
            .thenReturn(Result.success(""))
        whenever(mockObjectStore.listFiles(backupPath)).thenReturn(emptyList())

        val result = service.backup(testControlHost, backupName, accountBucket, clusterName)

        assertThat(result.isSuccess).isTrue()

        verify(mockPodOps).execInPod(
            controlHost = eq(testControlHost),
            namespace = eq(Constants.ClickHouse.NAMESPACE),
            podName = eq("clickhouse-0"),
            command = argThat { any { it.contains("BACKUP DATABASE default") } && any { it.contains(backupName) } },
        )
        verify(mockObjectStore).uploadContent(
            argThat { contains(backupName) && contains(clusterName) },
            any(),
        )
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.BackupStarting })
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.BackupComplete })
    }

    @Test
    fun `restore fails when backup not found`() {
        val metadataPath =
            clickhouseBackupsRoot(accountBucket)
                .resolve(backupName)
                .resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)
        whenever(mockObjectStore.fileExists(metadataPath)).thenReturn(false)

        val result = service.restore(testControlHost, backupName, accountBucket)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("not found")
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.BackupNotFound })
        verify(mockPodOps, never()).execInPod(any(), any(), any(), any())
    }

    @Test
    fun `restore succeeds when backup exists`() {
        val metadataPath =
            clickhouseBackupsRoot(accountBucket)
                .resolve(backupName)
                .resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)
        whenever(mockObjectStore.fileExists(metadataPath)).thenReturn(true)
        whenever(mockPodOps.execInPod(any(), any(), any(), any()))
            .thenReturn(Result.success(""))

        val result = service.restore(testControlHost, backupName, accountBucket)

        assertThat(result.isSuccess).isTrue()

        verify(mockPodOps).execInPod(
            controlHost = eq(testControlHost),
            namespace = eq(Constants.ClickHouse.NAMESPACE),
            podName = eq("clickhouse-0"),
            command = argThat { any { it.contains("RESTORE DATABASE default") } && any { it.contains(backupName) } },
        )
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.RestoreStarting })
        verify(mockEventBus).emit(argThat { this is Event.ClickHouse.Backup.RestoreComplete })
    }

    @Test
    fun `listBackups returns empty list when no backups found`() {
        val backupsRoot = clickhouseBackupsRoot(accountBucket)
        whenever(mockObjectStore.listFiles(backupsRoot, recursive = false)).thenReturn(emptyList())

        val result = service.listBackups(accountBucket)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun `listBackups returns sorted backups when metadata files exist`() {
        val backupsRoot = clickhouseBackupsRoot(accountBucket)
        val dir1 = backupsRoot.resolve("backup-a")
        val dir2 = backupsRoot.resolve("backup-b")
        val metadataPath1 = dir1.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)
        val metadataPath2 = dir2.resolve(Constants.ClickHouse.BACKUP_METADATA_FILE)

        whenever(mockObjectStore.listFiles(backupsRoot, recursive = false))
            .thenReturn(
                listOf(
                    ObjectStore.FileInfo(dir1, 0L, ""),
                    ObjectStore.FileInfo(dir2, 0L, ""),
                ),
            )

        val metadata1 =
            """{"backupName":"backup-a","timestamp":"2024-01-01T10:00:00Z","sourceCluster":"cluster1","totalSizeBytes":1000}"""
        val metadata2 =
            """{"backupName":"backup-b","timestamp":"2024-01-02T10:00:00Z","sourceCluster":"cluster1","totalSizeBytes":2000}"""

        whenever(mockObjectStore.readContent(metadataPath1)).thenReturn(metadata1)
        whenever(mockObjectStore.readContent(metadataPath2)).thenReturn(metadata2)

        val result = service.listBackups(accountBucket)

        assertThat(result.isSuccess).isTrue()
        val backups = result.getOrThrow()
        assertThat(backups).hasSize(2)
        // Should be sorted descending by timestamp — backup-b first
        assertThat(backups[0].backupName).isEqualTo("backup-b")
        assertThat(backups[1].backupName).isEqualTo("backup-a")
    }
}
