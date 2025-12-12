package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for BackupRestoreService.
 *
 * This service coordinates StateReconstructionService and ClusterBackupService
 * to provide unified backup/restore operations.
 */
internal class BackupRestoreServiceTest {
    private val mockStateReconstructionService: StateReconstructionService = mock()
    private val mockClusterBackupService: ClusterBackupService = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockOutputHandler: OutputHandler = mock()

    @TempDir
    lateinit var tempDir: File

    private lateinit var service: BackupRestoreService

    @BeforeEach
    fun setUp() {
        service =
            DefaultBackupRestoreService(
                mockStateReconstructionService,
                mockClusterBackupService,
                mockClusterStateManager,
                mockOutputHandler,
            )
    }

    @Nested
    inner class RestoreFromVpc {
        @Test
        fun `should reconstruct state and restore files from S3`() {
            // Given
            val vpcId = "vpc-12345"
            val reconstructedState = createClusterState(s3Bucket = "test-bucket")
            val restoreResult =
                RestoreResult(
                    successfulTargets = setOf(BackupTarget.KUBECONFIG, BackupTarget.K8S_MANIFESTS),
                    filesRestored = 2,
                )

            whenever(mockClusterStateManager.exists()).thenReturn(false)
            whenever(mockStateReconstructionService.reconstructFromVpc(vpcId)).thenReturn(reconstructedState)
            whenever(mockClusterBackupService.restoreAll(any(), eq(reconstructedState)))
                .thenReturn(Result.success(restoreResult))

            // When
            val result = service.restoreFromVpc(vpcId, tempDir.absolutePath)

            // Then
            assertThat(result.isSuccess).isTrue()
            val vpcRestoreResult = result.getOrThrow()
            assertThat(vpcRestoreResult.clusterState).isEqualTo(reconstructedState)
            assertThat(vpcRestoreResult.restoreResult).isEqualTo(restoreResult)

            verify(mockStateReconstructionService).reconstructFromVpc(vpcId)
            verify(mockClusterStateManager).save(reconstructedState)
            verify(mockClusterBackupService).restoreAll(tempDir.absolutePath, reconstructedState)
        }

        @Test
        fun `should skip file restore when no S3 bucket configured`() {
            // Given
            val vpcId = "vpc-12345"
            val reconstructedState = createClusterState(s3Bucket = null)

            whenever(mockClusterStateManager.exists()).thenReturn(false)
            whenever(mockStateReconstructionService.reconstructFromVpc(vpcId)).thenReturn(reconstructedState)

            // When
            val result = service.restoreFromVpc(vpcId, tempDir.absolutePath)

            // Then
            assertThat(result.isSuccess).isTrue()
            val vpcRestoreResult = result.getOrThrow()
            assertThat(vpcRestoreResult.clusterState).isEqualTo(reconstructedState)
            assertThat(vpcRestoreResult.restoreResult).isNull()

            verify(mockStateReconstructionService).reconstructFromVpc(vpcId)
            verify(mockClusterStateManager).save(reconstructedState)
            verify(mockClusterBackupService, never()).restoreAll(any(), any())
        }

        @Test
        fun `should fail when state exists and force is false`() {
            // Given
            val vpcId = "vpc-12345"
            whenever(mockClusterStateManager.exists()).thenReturn(true)

            // When
            val result = service.restoreFromVpc(vpcId, tempDir.absolutePath, force = false)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("state.json already exists")

            verify(mockStateReconstructionService, never()).reconstructFromVpc(any())
            verify(mockClusterStateManager, never()).save(any())
        }

        @Test
        fun `should overwrite state when force is true`() {
            // Given
            val vpcId = "vpc-12345"
            val reconstructedState = createClusterState(s3Bucket = null)

            whenever(mockClusterStateManager.exists()).thenReturn(true)
            whenever(mockStateReconstructionService.reconstructFromVpc(vpcId)).thenReturn(reconstructedState)

            // When
            val result = service.restoreFromVpc(vpcId, tempDir.absolutePath, force = true)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockStateReconstructionService).reconstructFromVpc(vpcId)
            verify(mockClusterStateManager).save(reconstructedState)
        }

        @Test
        fun `should propagate state reconstruction failure`() {
            // Given
            val vpcId = "vpc-12345"
            val reconstructionError = RuntimeException("VPC not found")

            whenever(mockClusterStateManager.exists()).thenReturn(false)
            whenever(mockStateReconstructionService.reconstructFromVpc(vpcId)).thenThrow(reconstructionError)

            // When
            val result = service.restoreFromVpc(vpcId, tempDir.absolutePath)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isEqualTo(reconstructionError)
            verify(mockClusterStateManager, never()).save(any())
        }
    }

    @Nested
    inner class BackupAll {
        @Test
        fun `should delegate to ClusterBackupService`() {
            // Given
            val clusterState = createClusterState(s3Bucket = "test-bucket")
            val backupResult =
                BackupResult(
                    successfulTargets = setOf(BackupTarget.KUBECONFIG),
                    filesBackedUp = 1,
                )
            whenever(mockClusterBackupService.backupAll(any(), eq(clusterState)))
                .thenReturn(Result.success(backupResult))

            // When
            val result = service.backupAll(tempDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isEqualTo(backupResult)
            verify(mockClusterBackupService).backupAll(tempDir.absolutePath, clusterState)
        }
    }

    @Nested
    inner class BackupChanged {
        @Test
        fun `should delegate to ClusterBackupService`() {
            // Given
            val clusterState = createClusterState(s3Bucket = "test-bucket")
            val incrementalResult =
                IncrementalBackupResult(
                    filesChecked = 3,
                    filesUploaded = 2,
                    filesSkipped = 1,
                    updatedHashes = mapOf("kubeconfig" to "hash1"),
                )
            whenever(mockClusterBackupService.backupChanged(any(), eq(clusterState)))
                .thenReturn(Result.success(incrementalResult))

            // When
            val result = service.backupChanged(tempDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isEqualTo(incrementalResult)
            verify(mockClusterBackupService).backupChanged(tempDir.absolutePath, clusterState)
        }
    }

    @Nested
    inner class RestoreAll {
        @Test
        fun `should delegate to ClusterBackupService`() {
            // Given
            val clusterState = createClusterState(s3Bucket = "test-bucket")
            val restoreResult =
                RestoreResult(
                    successfulTargets = setOf(BackupTarget.KUBECONFIG, BackupTarget.CASSANDRA_PATCH),
                    filesRestored = 2,
                )
            whenever(mockClusterBackupService.restoreAll(any(), eq(clusterState)))
                .thenReturn(Result.success(restoreResult))

            // When
            val result = service.restoreAll(tempDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isEqualTo(restoreResult)
            verify(mockClusterBackupService).restoreAll(tempDir.absolutePath, clusterState)
        }
    }

    private fun createClusterState(s3Bucket: String? = "test-bucket"): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = s3Bucket,
            clusterId = "test-cluster-id",
            hosts =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            ClusterHost("1.2.3.4", "10.0.1.10", "db0", "us-west-2a"),
                        ),
                ),
        )
}
