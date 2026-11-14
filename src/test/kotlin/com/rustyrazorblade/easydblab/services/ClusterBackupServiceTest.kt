package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for ClusterBackupService.
 *
 * This service handles backing up cluster configuration files (kubeconfig, k8s manifests,
 * cassandra.patch.yaml) to the object store, and restoring them when reconstructing state from an
 * existing VPC. The tests run against [InMemoryObjectStore]: a backup is checked by reading the
 * stored object back, a restore by reading the local file it wrote. Only the upload-failure tests
 * use a mock, to make the store throw.
 */
internal class ClusterBackupServiceTest {
    private val store = InMemoryObjectStore()
    private lateinit var eventBus: EventBus
    private val capturedEvents = mutableListOf<EventEnvelope>()

    @TempDir
    lateinit var tempDir: File

    private lateinit var service: ClusterBackupService

    @BeforeEach
    fun setUp() {
        capturedEvents.clear()
        eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope)
                }

                override fun close() = Unit
            },
        )
        service = DefaultClusterBackupService(store, eventBus)
    }

    private val clusterState: ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test-abc12345",
        )

    private val noBucketState: ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = null,
        )

    private val root: ClusterStoragePath get() = ClusterStoragePath.from(clusterState)

    private inline fun <reified T : Event> events(): List<T> = capturedEvents.map { it.event }.filterIsInstance<T>()

    private fun storedKeys(): List<String> = store.listFiles(root, recursive = true).map { it.path.getKey() }

    @Nested
    inner class BackupKubeconfig {
        @Test
        fun `uploads the kubeconfig to the cluster's kubeconfig path and reports where`() {
            val localKubeconfig = File(tempDir, "kubeconfig").apply { writeText("apiVersion: v1\nclusters: []") }

            val result = service.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(store.readContent(root.kubeconfig())).isEqualTo("apiVersion: v1\nclusters: []")
            assertThat(events<Event.Backup.KubeconfigBackedUp>().single().s3Path).isEqualTo(store.uriOf(root.kubeconfig()))
        }

        @Test
        fun `fails and stores nothing when the file does not exist`() {
            val result = service.backupKubeconfig(tempDir.toPath().resolve("does-not-exist"), clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("does not exist")
            assertThat(storedKeys()).isEmpty()
        }

        @Test
        fun `fails when no bucket is configured`() {
            val localKubeconfig = File(tempDir, "kubeconfig").apply { writeText("apiVersion: v1\nclusters: []") }

            val result = service.backupKubeconfig(localKubeconfig.toPath(), noBucketState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `fails and reports no backup when the upload throws`() {
            val failingStore: ObjectStore = mock()
            whenever(failingStore.uploadFile(any(), any(), any())).thenThrow(IllegalStateException("upload refused"))
            val failingService = DefaultClusterBackupService(failingStore, eventBus)
            val localKubeconfig = File(tempDir, "kubeconfig").apply { writeText("apiVersion: v1") }

            val result = failingService.backupKubeconfig(localKubeconfig.toPath(), clusterState)

            assertThat(result.exceptionOrNull()).hasMessage("upload refused")
            assertThat(events<Event.Backup.KubeconfigBackedUp>()).isEmpty()
        }
    }

    @Nested
    inner class RestoreKubeconfig {
        @Test
        fun `writes the stored kubeconfig to the local path and reports where`() {
            store.uploadContent("apiVersion: v1\nclusters: [restored]", root.kubeconfig())
            val localPath = tempDir.toPath().resolve("kubeconfig")

            val result = service.restoreKubeconfig(localPath, clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(localPath.toFile().readText()).isEqualTo("apiVersion: v1\nclusters: [restored]")
            assertThat(events<Event.Backup.KubeconfigRestored>().single().s3Path).isEqualTo(store.uriOf(root.kubeconfig()))
        }

        @Test
        fun `fails and writes nothing when no kubeconfig is stored`() {
            val localPath = tempDir.toPath().resolve("kubeconfig")

            val result = service.restoreKubeconfig(localPath, clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("does not exist in S3")
            assertThat(localPath.toFile()).doesNotExist()
        }

        @Test
        fun `fails when no bucket is configured`() {
            val result = service.restoreKubeconfig(tempDir.toPath().resolve("kubeconfig"), noBucketState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BackupK8sManifests {
        @Test
        fun `uploads every manifest under the cluster's k8s path`() {
            val k8sDir =
                File(tempDir, Constants.K8s.MANIFEST_DIR).apply {
                    mkdirs()
                    File(this, "deployment.yaml").writeText("apiVersion: apps/v1")
                    File(this, "service.yaml").writeText("apiVersion: v1")
                }

            val result = service.backupK8sManifests(k8sDir.toPath(), clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(store.readContent(root.k8s().resolve("deployment.yaml"))).isEqualTo("apiVersion: apps/v1")
            assertThat(store.readContent(root.k8s().resolve("service.yaml"))).isEqualTo("apiVersion: v1")
            assertThat(events<Event.Backup.K8sManifestsBackedUp>().single().s3Path).isEqualTo(store.uriOf(root.k8s()))
        }

        @Test
        fun `fails and stores nothing when the directory does not exist`() {
            val result = service.backupK8sManifests(tempDir.toPath().resolve("does-not-exist"), clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(storedKeys()).isEmpty()
        }
    }

    @Nested
    inner class RestoreK8sManifests {
        @Test
        fun `writes every stored manifest into the local directory`() {
            store.uploadContent("apiVersion: apps/v1", root.k8s().resolve("deployment.yaml"))
            store.uploadContent("apiVersion: v1", root.k8s().resolve("nested/service.yaml"))
            val localPath = tempDir.toPath().resolve(Constants.K8s.MANIFEST_DIR)

            val result = service.restoreK8sManifests(localPath, clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(localPath.resolve("deployment.yaml").toFile().readText()).isEqualTo("apiVersion: apps/v1")
            assertThat(localPath.resolve("nested/service.yaml").toFile().readText()).isEqualTo("apiVersion: v1")
            assertThat(events<Event.Backup.K8sManifestsRestored>()).hasSize(1)
        }

        @Test
        fun `fails and writes nothing when no manifests are stored`() {
            val localPath = tempDir.toPath().resolve(Constants.K8s.MANIFEST_DIR)

            val result = service.restoreK8sManifests(localPath, clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("do not exist in S3")
            assertThat(localPath.toFile()).doesNotExist()
        }
    }

    @Nested
    inner class BackupCassandraPatch {
        @Test
        fun `uploads the patch to the cluster's cassandra patch path`() {
            val localPatch =
                File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).apply { writeText("cassandra:\n  jvm_options: -Xmx4G") }

            val result = service.backupCassandraPatch(localPatch.toPath(), clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(store.readContent(root.cassandraPatch())).isEqualTo("cassandra:\n  jvm_options: -Xmx4G")
            assertThat(events<Event.Backup.CassandraPatchBackedUp>().single().s3Path).isEqualTo(store.uriOf(root.cassandraPatch()))
        }

        @Test
        fun `fails and stores nothing when the file does not exist`() {
            val result = service.backupCassandraPatch(tempDir.toPath().resolve("does-not-exist.yaml"), clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("does not exist")
            assertThat(storedKeys()).isEmpty()
        }

        @Test
        fun `fails when no bucket is configured`() {
            val localPatch = File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).apply { writeText("cassandra: {}") }

            val result = service.backupCassandraPatch(localPatch.toPath(), noBucketState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class RestoreCassandraPatch {
        @Test
        fun `writes the stored patch to the local path`() {
            store.uploadContent("cassandra:\n  jvm_options: -Xmx8G", root.cassandraPatch())
            val localPath = tempDir.toPath().resolve(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)

            val result = service.restoreCassandraPatch(localPath, clusterState)

            assertThat(result.isSuccess).isTrue()
            assertThat(localPath.toFile().readText()).isEqualTo("cassandra:\n  jvm_options: -Xmx8G")
            assertThat(events<Event.Backup.CassandraPatchRestored>()).hasSize(1)
        }

        @Test
        fun `fails and writes nothing when no patch is stored`() {
            val localPath = tempDir.toPath().resolve(Constants.ConfigPaths.CASSANDRA_PATCH_FILE)

            val result = service.restoreCassandraPatch(localPath, clusterState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("does not exist in S3")
            assertThat(localPath.toFile()).doesNotExist()
        }

        @Test
        fun `fails when no bucket is configured`() {
            val result = service.restoreCassandraPatch(tempDir.toPath().resolve("p.yaml"), noBucketState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BackupAll {
        @Test
        fun `stores each present target at its own path and counts every file`() {
            File(tempDir, Constants.K3s.LOCAL_KUBECONFIG).writeText("apiVersion: v1\nclusters: []")
            File(tempDir, Constants.K8s.MANIFEST_DIR).apply {
                mkdirs()
                File(this, "deployment.yaml").writeText("apiVersion: apps/v1")
            }
            File(tempDir, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).writeText("cassandra:\n  jvm_options: -Xmx4G")

            val backupResult = service.backupAll(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(backupResult.successfulTargets)
                .containsExactlyInAnyOrder(BackupTarget.KUBECONFIG, BackupTarget.K8S_MANIFESTS, BackupTarget.CASSANDRA_PATCH)
            assertThat(backupResult.filesBackedUp).isEqualTo(3)
            assertThat(store.readContent(root.kubeconfig())).isEqualTo("apiVersion: v1\nclusters: []")
            assertThat(store.readContent(root.k8s().resolve("deployment.yaml"))).isEqualTo("apiVersion: apps/v1")
            assertThat(store.readContent(root.cassandraPatch())).isEqualTo("cassandra:\n  jvm_options: -Xmx4G")
            assertThat(events<Event.Backup.ConfigBackedUp>()).hasSize(3)
        }

        @Test
        fun `skips targets that do not exist locally`() {
            val backupResult = service.backupAll(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(backupResult.successfulTargets).isEmpty()
            assertThat(backupResult.filesBackedUp).isEqualTo(0)
            assertThat(storedKeys()).isEmpty()
        }
    }

    @Nested
    inner class RestoreAll {
        @Test
        fun `restores each stored target to its local path`() {
            store.uploadContent("kubeconfig-content", root.kubeconfig())
            store.uploadContent("apiVersion: apps/v1", root.k8s().resolve("deployment.yaml"))
            store.uploadContent("apiVersion: v1", root.k8s().resolve("service.yaml"))
            store.uploadContent("patch-content", root.cassandraPatch())

            val restoreResult = service.restoreAll(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(restoreResult.successfulTargets)
                .containsExactlyInAnyOrder(BackupTarget.KUBECONFIG, BackupTarget.K8S_MANIFESTS, BackupTarget.CASSANDRA_PATCH)
            assertThat(restoreResult.filesRestored).isEqualTo(4)
            assertThat(File(tempDir, BackupTarget.KUBECONFIG.localPath).readText()).isEqualTo("kubeconfig-content")
            assertThat(File(tempDir, "${BackupTarget.K8S_MANIFESTS.localPath}/service.yaml").readText()).isEqualTo("apiVersion: v1")
            assertThat(File(tempDir, BackupTarget.CASSANDRA_PATCH.localPath).readText()).isEqualTo("patch-content")
        }

        @Test
        fun `skips targets that are not stored`() {
            val restoreResult = service.restoreAll(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(restoreResult.successfulTargets).isEmpty()
            assertThat(restoreResult.filesRestored).isEqualTo(0)
            assertThat(tempDir.listFiles()).isEmpty()
        }

        @Test
        fun `a backup restored into a fresh directory reproduces the files`() {
            File(tempDir, "src").mkdirs()
            File(tempDir, "src/${BackupTarget.KUBECONFIG.localPath}").writeText("kube")
            File(tempDir, "src/${BackupTarget.ENV_SCRIPT.localPath}").writeText("export A=1")
            service.backupAll(File(tempDir, "src").absolutePath, clusterState).getOrThrow()

            val target = File(tempDir, "restored").apply { mkdirs() }
            service.restoreAll(target.absolutePath, clusterState).getOrThrow()

            assertThat(File(target, BackupTarget.KUBECONFIG.localPath).readText()).isEqualTo("kube")
            assertThat(File(target, BackupTarget.ENV_SCRIPT.localPath).readText()).isEqualTo("export A=1")
        }
    }

    @Nested
    inner class ExistsChecks {
        @Test
        fun `kubeconfigExistsInS3 follows whether the kubeconfig is stored`() {
            assertThat(service.kubeconfigExistsInS3(clusterState)).isFalse()

            store.uploadContent("kube", root.kubeconfig())

            assertThat(service.kubeconfigExistsInS3(clusterState)).isTrue()
        }

        @Test
        fun `k8sManifestsExistInS3 follows whether any manifest is stored`() {
            assertThat(service.k8sManifestsExistInS3(clusterState)).isFalse()

            store.uploadContent("apiVersion: v1", root.k8s().resolve("service.yaml"))

            assertThat(service.k8sManifestsExistInS3(clusterState)).isTrue()
        }

        @Test
        fun `cassandraPatchExistsInS3 follows whether the patch is stored`() {
            assertThat(service.cassandraPatchExistsInS3(clusterState)).isFalse()

            store.uploadContent("patch", root.cassandraPatch())

            assertThat(service.cassandraPatchExistsInS3(clusterState)).isTrue()
        }

        @Test
        fun `every exists check is false when no bucket is configured`() {
            assertThat(service.kubeconfigExistsInS3(noBucketState)).isFalse()
            assertThat(service.k8sManifestsExistInS3(noBucketState)).isFalse()
            assertThat(service.cassandraPatchExistsInS3(noBucketState)).isFalse()
        }
    }

    @Nested
    inner class BackupChanged {
        @Test
        fun `uploads every present file on the first backup`() {
            File(tempDir, "kubeconfig").writeText("apiVersion: v1\nclusters: []")
            File(tempDir, "cassandra.patch.yaml").writeText("cassandra:\n  jvm_options: -Xmx4G")

            val backupResult = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(backupResult.filesUploaded).isEqualTo(2)
            assertThat(backupResult.filesSkipped).isEqualTo(0)
            assertThat(backupResult.updatedHashes).containsOnlyKeys(BackupTarget.KUBECONFIG.name, BackupTarget.CASSANDRA_PATCH.name)
            assertThat(store.readContent(root.kubeconfig())).isEqualTo("apiVersion: v1\nclusters: []")
            assertThat(store.readContent(root.cassandraPatch())).isEqualTo("cassandra:\n  jvm_options: -Xmx4G")
        }

        @Test
        fun `leaves an unchanged file's stored copy alone`() {
            val kubeconfigContent = "apiVersion: v1\nclusters: []"
            File(tempDir, "kubeconfig").writeText(kubeconfigContent)
            store.uploadContent("previous backup", root.kubeconfig())
            val state = clusterState.copy(backupHashes = mapOf(BackupTarget.KUBECONFIG.name to computeHash(kubeconfigContent)))

            val backupResult = service.backupChanged(tempDir.absolutePath, state).getOrThrow()

            assertThat(backupResult.filesUploaded).isEqualTo(0)
            assertThat(backupResult.filesSkipped).isEqualTo(1)
            assertThat(backupResult.updatedHashes).isEmpty()
            assertThat(store.readContent(root.kubeconfig())).isEqualTo("previous backup")
        }

        @Test
        fun `uploads only the files whose hash changed`() {
            val kubeconfigContent = "apiVersion: v1\nclusters: []"
            File(tempDir, "kubeconfig").writeText(kubeconfigContent)
            File(tempDir, "cassandra.patch.yaml").writeText("cassandra:\n  jvm_options: -Xmx4G")
            store.uploadContent("previous backup", root.kubeconfig())
            val state = clusterState.copy(backupHashes = mapOf(BackupTarget.KUBECONFIG.name to computeHash(kubeconfigContent)))

            val backupResult = service.backupChanged(tempDir.absolutePath, state).getOrThrow()

            assertThat(backupResult.filesUploaded).isEqualTo(1)
            assertThat(backupResult.filesSkipped).isEqualTo(1)
            assertThat(backupResult.updatedHashes).containsOnlyKeys(BackupTarget.CASSANDRA_PATCH.name)
            assertThat(store.readContent(root.kubeconfig())).isEqualTo("previous backup")
            assertThat(store.readContent(root.cassandraPatch())).isEqualTo("cassandra:\n  jvm_options: -Xmx4G")
        }

        @Test
        fun `uploads a directory target with every file in it`() {
            File(tempDir, "k8s").mkdirs()
            File(tempDir, "k8s/deployment.yaml").writeText("apiVersion: apps/v1")
            File(tempDir, "k8s/service.yaml").writeText("apiVersion: v1")

            val backupResult = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow()

            assertThat(backupResult.updatedHashes).containsKey(BackupTarget.K8S_MANIFESTS.name)
            assertThat(store.readContent(root.k8s().resolve("deployment.yaml"))).isEqualTo("apiVersion: apps/v1")
            assertThat(store.readContent(root.k8s().resolve("service.yaml"))).isEqualTo("apiVersion: v1")
        }

        @Test
        fun `fails and stores nothing when no bucket is configured`() {
            File(tempDir, "kubeconfig").writeText("apiVersion: v1")

            val result = service.backupChanged(tempDir.absolutePath, noBucketState)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(storedKeys()).isEmpty()
        }

        @Test
        fun `fails rather than returning hashes when an upload throws`() {
            val failingStore: ObjectStore = mock()
            whenever(failingStore.uploadFile(any(), any(), any())).thenThrow(IllegalStateException("upload refused"))
            File(tempDir, "kubeconfig").writeText("apiVersion: v1")

            val result = DefaultClusterBackupService(failingStore, eventBus).backupChanged(tempDir.absolutePath, clusterState)

            // A failed upload must not hand back a hash, or the next run would skip the file as unchanged.
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessage("upload refused")
        }

        @Test
        fun `the same content always gives the same hash`() {
            File(tempDir, "kubeconfig").writeText("consistent content")

            val hash1 = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow().updatedHashes
            val hash2 = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow().updatedHashes

            assertThat(hash1.getValue(BackupTarget.KUBECONFIG.name)).isEqualTo(computeHash("consistent content"))
            assertThat(hash2).isEqualTo(hash1)
        }

        @Test
        fun `changed content gives a different hash`() {
            File(tempDir, "kubeconfig").writeText("content version 1")
            val hash1 = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow().updatedHashes

            File(tempDir, "kubeconfig").writeText("content version 2")
            val hash2 = service.backupChanged(tempDir.absolutePath, clusterState).getOrThrow().updatedHashes

            assertThat(hash2.getValue(BackupTarget.KUBECONFIG.name)).isNotEqualTo(hash1.getValue(BackupTarget.KUBECONFIG.name))
        }

        /** SHA-256 of a string, as the service hashes a single file. */
        private fun computeHash(content: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(content.toByteArray())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
