package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.io.File

/**
 * Integration tests for ClusterBackupService using LocalStack S3.
 *
 * These tests verify actual S3 operations including upload, download, and incremental backup
 * functionality against a real S3-compatible storage backend.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterBackupServiceS3IntegrationTest {
    companion object {
        private const val TEST_BUCKET = "easy-db-lab-test-bucket"

        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.S3)
    }

    private lateinit var s3Client: S3Client
    private lateinit var objectStore: ObjectStore
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var service: ClusterBackupService

    @TempDir
    lateinit var tempDir: File

    @BeforeAll
    fun setupS3Client() {
        s3Client =
            S3Client
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.S3))
                .region(Region.of(localStack.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            localStack.accessKey,
                            localStack.secretKey,
                        ),
                    ),
                ).forcePathStyle(true)
                .build()

        // Create test bucket
        s3Client.createBucket(
            CreateBucketRequest
                .builder()
                .bucket(TEST_BUCKET)
                .build(),
        )
    }

    @BeforeEach
    fun setup() {
        outputHandler = BufferedOutputHandler()
        objectStore = S3ObjectStore(s3Client, outputHandler)
        service = DefaultClusterBackupService(objectStore, outputHandler)
    }

    private fun createClusterState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = TEST_BUCKET,
        )

    @Nested
    inner class BackupAndRestoreKubeconfig {
        @Test
        fun `should upload and download kubeconfig file`() {
            // Given
            val kubeconfigContent = "apiVersion: v1\nclusters: []\nkind: Config"
            val localKubeconfig =
                File(tempDir, "kubeconfig").apply {
                    writeText(kubeconfigContent)
                }
            val clusterState = createClusterState()

            // When - backup
            val backupResult = service.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            // Then - backup succeeded
            assertThat(backupResult.isSuccess).isTrue()

            // When - restore to different location
            val restoredPath = tempDir.toPath().resolve("restored-kubeconfig")
            val restoreResult = service.restoreKubeconfig(restoredPath, clusterState)

            // Then - restore succeeded and content matches
            assertThat(restoreResult.isSuccess).isTrue()
            assertThat(restoredPath.toFile().readText()).isEqualTo(kubeconfigContent)
        }

        @Test
        fun `kubeconfigExistsInS3 should return true after backup`() {
            // Given
            val localKubeconfig =
                File(tempDir, "kubeconfig").apply {
                    writeText("test-content")
                }
            val clusterState = createClusterState()

            // When
            service.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            // Then
            assertThat(service.kubeconfigExistsInS3(clusterState)).isTrue()
        }
    }

    @Nested
    inner class BackupAndRestoreK8sManifests {
        @Test
        fun `should upload and download k8s manifests directory`() {
            // Given
            val k8sDir =
                File(tempDir, "k8s").apply {
                    mkdirs()
                    File(this, "deployment.yaml").writeText("apiVersion: apps/v1\nkind: Deployment")
                    File(this, "service.yaml").writeText("apiVersion: v1\nkind: Service")
                }
            val clusterState = createClusterState()

            // When - backup
            val backupResult = service.backupK8sManifests(k8sDir.toPath(), clusterState)

            // Then - backup succeeded
            assertThat(backupResult.isSuccess).isTrue()

            // When - restore to different location
            val restoredDir = File(tempDir, "restored-k8s")
            val restoreResult = service.restoreK8sManifests(restoredDir.toPath(), clusterState)

            // Then - restore succeeded and content matches
            assertThat(restoreResult.isSuccess).isTrue()
            assertThat(File(restoredDir, "deployment.yaml").readText())
                .isEqualTo("apiVersion: apps/v1\nkind: Deployment")
            assertThat(File(restoredDir, "service.yaml").readText())
                .isEqualTo("apiVersion: v1\nkind: Service")
        }

        @Test
        fun `k8sManifestsExistInS3 should return true after backup`() {
            // Given
            val k8sDir =
                File(tempDir, "k8s-check").apply {
                    mkdirs()
                    File(this, "test.yaml").writeText("test")
                }
            val clusterState = createClusterState()

            // When
            service.backupK8sManifests(k8sDir.toPath(), clusterState)

            // Then
            assertThat(service.k8sManifestsExistInS3(clusterState)).isTrue()
        }
    }

    @Nested
    inner class BackupAndRestoreCassandraPatch {
        @Test
        fun `should upload and download cassandra patch file`() {
            // Given
            val patchContent = "concurrent_reads: 64\nconcurrent_writes: 64"
            val localPatch =
                File(tempDir, "cassandra.patch.yaml").apply {
                    writeText(patchContent)
                }
            val clusterState = createClusterState()

            // When - backup
            val backupResult = service.backupCassandraPatch(localPatch.toPath(), clusterState)

            // Then - backup succeeded
            assertThat(backupResult.isSuccess).isTrue()

            // When - restore to different location
            val restoredPath = tempDir.toPath().resolve("restored-cassandra.patch.yaml")
            val restoreResult = service.restoreCassandraPatch(restoredPath, clusterState)

            // Then - restore succeeded and content matches
            assertThat(restoreResult.isSuccess).isTrue()
            assertThat(restoredPath.toFile().readText()).isEqualTo(patchContent)
        }

        @Test
        fun `cassandraPatchExistsInS3 should return true after backup`() {
            // Given
            val localPatch =
                File(tempDir, "cassandra.patch.yaml").apply {
                    writeText("test-patch")
                }
            val clusterState = createClusterState()

            // When
            service.backupCassandraPatch(localPatch.toPath(), clusterState)

            // Then
            assertThat(service.cassandraPatchExistsInS3(clusterState)).isTrue()
        }
    }

    @Nested
    inner class BackupAll {
        @Test
        fun `should backup all existing config files`() {
            // Given - create test files for backup
            val workDir =
                File(tempDir, "backup-all-test").apply {
                    mkdirs()
                    File(this, "kubeconfig").writeText("kubeconfig-content")
                    File(this, "k8s").apply {
                        mkdirs()
                        File(this, "manifest.yaml").writeText("manifest-content")
                    }
                    File(this, "cassandra.patch.yaml").writeText("patch-content")
                    File(this, "cassandra").apply {
                        mkdirs()
                        File(this, "cassandra.yaml").writeText("cassandra-config")
                    }
                    File(this, "cassandra_versions.yaml").writeText("versions-content")
                    File(this, "environment.sh").writeText("ENV_VAR=value")
                    File(this, "setup_instance.sh").writeText("#!/bin/bash\necho setup")
                }
            val clusterState = createClusterState()

            // When
            val result = service.backupAll(workDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val backupResult = result.getOrThrow()
            assertThat(backupResult.hasBackups()).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.KUBECONFIG)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.K8S_MANIFESTS)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.CASSANDRA_PATCH)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.CASSANDRA_CONFIG)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.CASSANDRA_VERSIONS)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.ENVIRONMENT_SCRIPT)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.SETUP_INSTANCE_SCRIPT)).isTrue()
        }

        @Test
        fun `should skip missing files during backup`() {
            // Given - create only some files
            val workDir =
                File(tempDir, "partial-backup-test").apply {
                    mkdirs()
                    File(this, "kubeconfig").writeText("kubeconfig-content")
                    // No other files
                }
            val clusterState = createClusterState()

            // When
            val result = service.backupAll(workDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val backupResult = result.getOrThrow()
            assertThat(backupResult.isBackedUp(BackupTarget.KUBECONFIG)).isTrue()
            assertThat(backupResult.isBackedUp(BackupTarget.K8S_MANIFESTS)).isFalse()
            assertThat(backupResult.isBackedUp(BackupTarget.CASSANDRA_PATCH)).isFalse()
        }
    }

    @Nested
    inner class RestoreAll {
        @Test
        fun `should restore all backed up files`() {
            // Given - backup files first
            val sourceDir =
                File(tempDir, "restore-source").apply {
                    mkdirs()
                    File(this, "kubeconfig").writeText("kubeconfig-restored")
                    File(this, "k8s").apply {
                        mkdirs()
                        File(this, "deployment.yaml").writeText("deployment-restored")
                    }
                    File(this, "cassandra.patch.yaml").writeText("patch-restored")
                }
            val clusterState = createClusterState()
            service.backupAll(sourceDir.absolutePath, clusterState)

            // When - restore to new location
            val restoreDir =
                File(tempDir, "restore-target").apply {
                    mkdirs()
                }
            val result = service.restoreAll(restoreDir.absolutePath, clusterState)

            // Then
            assertThat(result.isSuccess).isTrue()
            val restoreResult = result.getOrThrow()
            assertThat(restoreResult.hasRestores()).isTrue()
            assertThat(restoreResult.isRestored(BackupTarget.KUBECONFIG)).isTrue()
            assertThat(restoreResult.isRestored(BackupTarget.K8S_MANIFESTS)).isTrue()
            assertThat(restoreResult.isRestored(BackupTarget.CASSANDRA_PATCH)).isTrue()

            // Verify content
            assertThat(File(restoreDir, "kubeconfig").readText()).isEqualTo("kubeconfig-restored")
            assertThat(File(restoreDir, "k8s/deployment.yaml").readText()).isEqualTo("deployment-restored")
            assertThat(File(restoreDir, "cassandra.patch.yaml").readText()).isEqualTo("patch-restored")
        }
    }

    @Nested
    inner class IncrementalBackup {
        @Test
        fun `should only upload changed files`() {
            // Given - initial backup
            val workDir =
                File(tempDir, "incremental-test").apply {
                    mkdirs()
                    File(this, "kubeconfig").writeText("initial-kubeconfig")
                    File(this, "cassandra.patch.yaml").writeText("initial-patch")
                }
            val clusterState = createClusterState()

            // First backup - both files should be uploaded
            val firstResult = service.backupChanged(workDir.absolutePath, clusterState)
            assertThat(firstResult.isSuccess).isTrue()
            val firstBackup = firstResult.getOrThrow()
            assertThat(firstBackup.filesUploaded).isEqualTo(2)

            // Update cluster state with hashes from first backup
            clusterState.backupHashes = clusterState.backupHashes + firstBackup.updatedHashes

            // When - second backup without changes
            val secondResult = service.backupChanged(workDir.absolutePath, clusterState)

            // Then - no files should be uploaded
            assertThat(secondResult.isSuccess).isTrue()
            val secondBackup = secondResult.getOrThrow()
            assertThat(secondBackup.filesUploaded).isZero()
            assertThat(secondBackup.filesSkipped).isEqualTo(2)
        }

        @Test
        fun `should upload only modified files`() {
            // Given - initial backup
            val workDir =
                File(tempDir, "incremental-modified-test").apply {
                    mkdirs()
                    File(this, "kubeconfig").writeText("initial-kubeconfig")
                    File(this, "cassandra.patch.yaml").writeText("initial-patch")
                }
            val clusterState = createClusterState()

            // First backup
            val firstResult = service.backupChanged(workDir.absolutePath, clusterState)
            clusterState.backupHashes = clusterState.backupHashes + firstResult.getOrThrow().updatedHashes

            // Modify one file
            File(workDir, "kubeconfig").writeText("modified-kubeconfig")

            // When - second backup with one changed file
            val secondResult = service.backupChanged(workDir.absolutePath, clusterState)

            // Then - only modified file should be uploaded
            assertThat(secondResult.isSuccess).isTrue()
            val secondBackup = secondResult.getOrThrow()
            assertThat(secondBackup.filesUploaded).isEqualTo(1)
            assertThat(secondBackup.filesSkipped).isEqualTo(1)
            assertThat(secondBackup.updatedHashes).containsKey(BackupTarget.KUBECONFIG.name)
            assertThat(secondBackup.updatedHashes).doesNotContainKey(BackupTarget.CASSANDRA_PATCH.name)
        }

        @Test
        fun `should detect directory content changes`() {
            // Given - initial backup with directory
            val workDir =
                File(tempDir, "incremental-dir-test").apply {
                    mkdirs()
                    File(this, "k8s").apply {
                        mkdirs()
                        File(this, "deployment.yaml").writeText("initial-deployment")
                    }
                }
            val clusterState = createClusterState()

            // First backup
            val firstResult = service.backupChanged(workDir.absolutePath, clusterState)
            clusterState.backupHashes = clusterState.backupHashes + firstResult.getOrThrow().updatedHashes

            // Modify file in directory
            File(workDir, "k8s/deployment.yaml").writeText("modified-deployment")

            // When - second backup
            val secondResult = service.backupChanged(workDir.absolutePath, clusterState)

            // Then - directory should be re-uploaded
            assertThat(secondResult.isSuccess).isTrue()
            val secondBackup = secondResult.getOrThrow()
            assertThat(secondBackup.filesUploaded).isEqualTo(1)
            assertThat(secondBackup.updatedHashes).containsKey(BackupTarget.K8S_MANIFESTS.name)
        }

        @Test
        fun `should detect new file added to directory`() {
            // Given - initial backup with directory
            val workDir =
                File(tempDir, "incremental-newfile-test").apply {
                    mkdirs()
                    File(this, "k8s").apply {
                        mkdirs()
                        File(this, "deployment.yaml").writeText("deployment")
                    }
                }
            val clusterState = createClusterState()

            // First backup
            val firstResult = service.backupChanged(workDir.absolutePath, clusterState)
            clusterState.backupHashes = clusterState.backupHashes + firstResult.getOrThrow().updatedHashes

            // Add new file to directory
            File(workDir, "k8s/service.yaml").writeText("new-service")

            // When - second backup
            val secondResult = service.backupChanged(workDir.absolutePath, clusterState)

            // Then - directory should be re-uploaded due to new file
            assertThat(secondResult.isSuccess).isTrue()
            val secondBackup = secondResult.getOrThrow()
            assertThat(secondBackup.filesUploaded).isEqualTo(1)
            assertThat(secondBackup.updatedHashes).containsKey(BackupTarget.K8S_MANIFESTS.name)
        }
    }
}
