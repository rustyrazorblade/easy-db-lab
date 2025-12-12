package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
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
import java.nio.file.Path

/**
 * Tests for ClusterBackupService.
 *
 * This service handles backing up cluster configuration files (kubeconfig, k8s manifests,
 * cassandra.patch.yaml) to S3, and restoring them when reconstructing state from an existing VPC.
 */
internal class ClusterBackupServiceTest {
    private val mockObjectStore: ObjectStore = mock()
    private val mockOutputHandler: OutputHandler = mock()

    @TempDir
    lateinit var tempDir: File

    private lateinit var service: ClusterBackupService

    @BeforeEach
    fun setUp() {
        service = DefaultClusterBackupService(mockObjectStore, mockOutputHandler)
    }

    @Nested
    inner class BackupKubeconfig {
        @Test
        fun `should upload kubeconfig file when it exists`() {
            // Given
            val localKubeconfig =
                File(tempDir, "kubeconfig").apply {
                    writeText("apiVersion: v1\nclusters: []")
                }
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).kubeconfig()

            whenever(mockObjectStore.uploadFile(any<File>(), any(), any()))
                .thenReturn(ObjectStore.UploadResult(expectedS3Path, 100L))

            // When
            val result = service.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).uploadFile(eq(localKubeconfig), eq(expectedS3Path), eq(true))
            verify(mockOutputHandler).handleMessage("Kubeconfig backed up to S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when file does not exist`() {
            // Given
            val nonExistentPath = tempDir.toPath().resolve("does-not-exist")
            val clusterState = createClusterState()

            // When
            val result = service.backupKubeconfig(nonExistentPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("does not exist")
            verify(mockObjectStore, never()).uploadFile(any<File>(), any(), any())
        }

        @Test
        fun `should return failure when s3Bucket is not configured`() {
            // Given
            val localKubeconfig =
                File(tempDir, "kubeconfig").apply {
                    writeText("apiVersion: v1\nclusters: []")
                }
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            verify(mockObjectStore, never()).uploadFile(any<File>(), any(), any())
        }
    }

    @Nested
    inner class RestoreKubeconfig {
        @Test
        fun `should download kubeconfig file when it exists in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve("kubeconfig")
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).kubeconfig()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(true)
            whenever(mockObjectStore.downloadFile(any(), any(), any()))
                .thenReturn(ObjectStore.DownloadResult(localPath, 100L))

            // When
            val result = service.restoreKubeconfig(localPath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).downloadFile(eq(expectedS3Path), eq(localPath), eq(true))
            verify(mockOutputHandler).handleMessage("Kubeconfig restored from S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when kubeconfig does not exist in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve("kubeconfig")
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).kubeconfig()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(false)

            // When
            val result = service.restoreKubeconfig(localPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("does not exist in S3")
            verify(mockObjectStore, never()).downloadFile(any(), any(), any())
        }

        @Test
        fun `should return failure when s3Bucket is not configured`() {
            // Given
            val localPath = tempDir.toPath().resolve("kubeconfig")
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.restoreKubeconfig(localPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            verify(mockObjectStore, never()).downloadFile(any(), any(), any())
        }
    }

    @Nested
    inner class BackupK8sManifests {
        @Test
        fun `should upload k8s manifests directory when it exists`() {
            // Given
            val k8sDir =
                File(tempDir, Constants.K8s.MANIFEST_DIR).apply {
                    mkdirs()
                    File(this, "deployment.yaml").writeText("apiVersion: apps/v1")
                    File(this, "service.yaml").writeText("apiVersion: v1")
                }
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).k8s()

            whenever(mockObjectStore.uploadDirectory(any<Path>(), any(), any()))
                .thenReturn(ObjectStore.UploadDirectoryResult(expectedS3Path, 2, 100L))

            // When
            val result = service.backupK8sManifests(k8sDir.toPath(), clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).uploadDirectory(eq(k8sDir.toPath()), eq(expectedS3Path), eq(true))
            verify(mockOutputHandler).handleMessage("K8s manifests backed up to S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when directory does not exist`() {
            // Given
            val nonExistentPath = tempDir.toPath().resolve("does-not-exist")
            val clusterState = createClusterState()

            // When
            val result = service.backupK8sManifests(nonExistentPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            verify(mockObjectStore, never()).uploadDirectory(any<Path>(), any(), any())
        }
    }

    @Nested
    inner class RestoreK8sManifests {
        @Test
        fun `should download k8s manifests directory when it exists in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve(Constants.K8s.MANIFEST_DIR)
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).k8s()

            whenever(mockObjectStore.directoryExists(expectedS3Path)).thenReturn(true)
            whenever(mockObjectStore.downloadDirectory(any(), any(), any()))
                .thenReturn(ObjectStore.DownloadDirectoryResult(localPath, 2, 100L))

            // When
            val result = service.restoreK8sManifests(localPath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).downloadDirectory(eq(expectedS3Path), eq(localPath), eq(true))
            verify(mockOutputHandler).handleMessage("K8s manifests restored from S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when k8s manifests do not exist in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve(Constants.K8s.MANIFEST_DIR)
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).k8s()

            whenever(mockObjectStore.directoryExists(expectedS3Path)).thenReturn(false)

            // When
            val result = service.restoreK8sManifests(localPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("do not exist in S3")
            verify(mockObjectStore, never()).downloadDirectory(any(), any(), any())
        }
    }

    @Nested
    inner class BackupCassandraPatch {
        @Test
        fun `should upload cassandra patch file when it exists`() {
            // Given
            val localPatch =
                File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).apply {
                    writeText("cassandra:\n  jvm_options: -Xmx4G")
                }
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.uploadFile(any<File>(), any(), any()))
                .thenReturn(ObjectStore.UploadResult(expectedS3Path, 50L))

            // When
            val result = service.backupCassandraPatch(localPatch.toPath(), clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).uploadFile(eq(localPatch), eq(expectedS3Path), eq(true))
            verify(mockOutputHandler).handleMessage("Cassandra patch backed up to S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when file does not exist`() {
            // Given
            val nonExistentPath = tempDir.toPath().resolve("does-not-exist.yaml")
            val clusterState = createClusterState()

            // When
            val result = service.backupCassandraPatch(nonExistentPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("does not exist")
            verify(mockObjectStore, never()).uploadFile(any<File>(), any(), any())
        }

        @Test
        fun `should return failure when s3Bucket is not configured`() {
            // Given
            val localPatch =
                File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).apply {
                    writeText("cassandra:\n  jvm_options: -Xmx4G")
                }
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.backupCassandraPatch(localPatch.toPath(), clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            verify(mockObjectStore, never()).uploadFile(any<File>(), any(), any())
        }
    }

    @Nested
    inner class RestoreCassandraPatch {
        @Test
        fun `should download cassandra patch file when it exists in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(true)
            whenever(mockObjectStore.downloadFile(any(), any(), any()))
                .thenReturn(ObjectStore.DownloadResult(localPath, 50L))

            // When
            val result = service.restoreCassandraPatch(localPath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            verify(mockObjectStore).downloadFile(eq(expectedS3Path), eq(localPath), eq(true))
            verify(mockOutputHandler).handleMessage("Cassandra patch restored from S3: ${expectedS3Path.toUri()}")
        }

        @Test
        fun `should return failure when cassandra patch does not exist in S3`() {
            // Given
            val localPath = tempDir.toPath().resolve(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(false)

            // When
            val result = service.restoreCassandraPatch(localPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("does not exist in S3")
            verify(mockObjectStore, never()).downloadFile(any(), any(), any())
        }

        @Test
        fun `should return failure when s3Bucket is not configured`() {
            // Given
            val localPath = tempDir.toPath().resolve(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.restoreCassandraPatch(localPath, clusterState)

            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            verify(mockObjectStore, never()).downloadFile(any(), any(), any())
        }
    }

    @Nested
    inner class BackupAll {
        @Test
        fun `should backup kubeconfig, k8s manifests, and cassandra patch when they exist`() {
            // Given
            val workingDir = tempDir.absolutePath
            File(tempDir, Constants.K3s.LOCAL_KUBECONFIG).apply {
                writeText("apiVersion: v1\nclusters: []")
            }
            File(tempDir, Constants.K8s.MANIFEST_DIR).apply {
                mkdirs()
                File(this, "deployment.yaml").writeText("apiVersion: apps/v1")
            }
            File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).apply {
                writeText("cassandra:\n  jvm_options: -Xmx4G")
            }
            val clusterState = createClusterState()

            whenever(mockObjectStore.uploadFile(any<File>(), any(), any()))
                .thenReturn(ObjectStore.UploadResult(ClusterS3Path.from(clusterState).kubeconfig(), 100L))
            whenever(mockObjectStore.uploadDirectory(any<Path>(), any(), any()))
                .thenReturn(ObjectStore.UploadDirectoryResult(ClusterS3Path.from(clusterState).k8s(), 1, 50L))

            // When
            val result = service.backupAll(workingDir, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val backupResult = result.getOrThrow()
            assertThat(backupResult.kubeconfigBackedUp).isTrue()
            assertThat(backupResult.k8sManifestsBackedUp).isTrue()
            assertThat(backupResult.cassandraPatchBackedUp).isTrue()
            assertThat(backupResult.filesBackedUp).isEqualTo(3) // kubeconfig + 1 manifest + cassandra.patch.yaml
        }

        @Test
        fun `should skip files that do not exist`() {
            // Given
            val workingDir = tempDir.absolutePath
            val clusterState = createClusterState()

            // When - no files exist
            val result = service.backupAll(workingDir, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val backupResult = result.getOrThrow()
            assertThat(backupResult.kubeconfigBackedUp).isFalse()
            assertThat(backupResult.k8sManifestsBackedUp).isFalse()
            assertThat(backupResult.cassandraPatchBackedUp).isFalse()
            assertThat(backupResult.filesBackedUp).isEqualTo(0)
        }
    }

    @Nested
    inner class RestoreAll {
        @Test
        fun `should restore kubeconfig, k8s manifests, and cassandra patch when they exist in S3`() {
            // Given
            val workingDir = tempDir.absolutePath
            val clusterState = createClusterState()
            val kubeconfigPath = ClusterS3Path.from(clusterState).kubeconfig()
            val k8sPath = ClusterS3Path.from(clusterState).k8s()
            val cassandraPatchPath = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.fileExists(kubeconfigPath)).thenReturn(true)
            whenever(mockObjectStore.fileExists(cassandraPatchPath)).thenReturn(true)
            whenever(mockObjectStore.directoryExists(k8sPath)).thenReturn(true)
            whenever(mockObjectStore.downloadFile(any(), any(), any()))
                .thenReturn(ObjectStore.DownloadResult(tempDir.toPath().resolve("kubeconfig"), 100L))
            whenever(mockObjectStore.downloadDirectory(any(), any(), any()))
                .thenReturn(ObjectStore.DownloadDirectoryResult(tempDir.toPath().resolve("k8s"), 2, 200L))

            // When
            val result = service.restoreAll(workingDir, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val restoreResult = result.getOrThrow()
            assertThat(restoreResult.kubeconfigRestored).isTrue()
            assertThat(restoreResult.k8sManifestsRestored).isTrue()
            assertThat(restoreResult.cassandraPatchRestored).isTrue()
        }

        @Test
        fun `should skip files that do not exist in S3`() {
            // Given
            val workingDir = tempDir.absolutePath
            val clusterState = createClusterState()
            val kubeconfigPath = ClusterS3Path.from(clusterState).kubeconfig()
            val k8sPath = ClusterS3Path.from(clusterState).k8s()
            val cassandraPatchPath = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.fileExists(kubeconfigPath)).thenReturn(false)
            whenever(mockObjectStore.fileExists(cassandraPatchPath)).thenReturn(false)
            whenever(mockObjectStore.directoryExists(k8sPath)).thenReturn(false)

            // When
            val result = service.restoreAll(workingDir, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val restoreResult = result.getOrThrow()
            assertThat(restoreResult.kubeconfigRestored).isFalse()
            assertThat(restoreResult.k8sManifestsRestored).isFalse()
            assertThat(restoreResult.cassandraPatchRestored).isFalse()
            assertThat(restoreResult.filesRestored).isEqualTo(0)
        }
    }

    @Nested
    inner class ExistsChecks {
        @Test
        fun `kubeconfigExistsInS3 should return true when kubeconfig exists`() {
            // Given
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).kubeconfig()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(true)

            // When
            val result = service.kubeconfigExistsInS3(clusterState)

            // Then
            assertThat(result).isTrue()
        }

        @Test
        fun `kubeconfigExistsInS3 should return false when s3Bucket is not configured`() {
            // Given
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.kubeconfigExistsInS3(clusterState)

            // Then
            assertThat(result).isFalse()
            verify(mockObjectStore, never()).fileExists(any())
        }

        @Test
        fun `k8sManifestsExistInS3 should return true when manifests exist`() {
            // Given
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).k8s()

            whenever(mockObjectStore.directoryExists(expectedS3Path)).thenReturn(true)

            // When
            val result = service.k8sManifestsExistInS3(clusterState)

            // Then
            assertThat(result).isTrue()
        }

        @Test
        fun `k8sManifestsExistInS3 should return false when s3Bucket is not configured`() {
            // Given
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.k8sManifestsExistInS3(clusterState)

            // Then
            assertThat(result).isFalse()
            verify(mockObjectStore, never()).directoryExists(any())
        }

        @Test
        fun `cassandraPatchExistsInS3 should return true when cassandra patch exists`() {
            // Given
            val clusterState = createClusterState()
            val expectedS3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            whenever(mockObjectStore.fileExists(expectedS3Path)).thenReturn(true)

            // When
            val result = service.cassandraPatchExistsInS3(clusterState)

            // Then
            assertThat(result).isTrue()
        }

        @Test
        fun `cassandraPatchExistsInS3 should return false when s3Bucket is not configured`() {
            // Given
            val clusterState =
                ClusterState(
                    name = "test-cluster",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )

            // When
            val result = service.cassandraPatchExistsInS3(clusterState)

            // Then
            assertThat(result).isFalse()
            verify(mockObjectStore, never()).fileExists(any())
        }
    }

    private fun createClusterState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test-abc12345",
        )
}
