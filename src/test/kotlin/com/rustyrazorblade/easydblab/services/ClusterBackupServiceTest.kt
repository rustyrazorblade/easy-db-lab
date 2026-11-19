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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
            s3Bucket = "easy-db-lab-test-abc12345",
        )

    private val noBucketState: ClusterState =
        ClusterState(
            name = "test-cluster",
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
    inner class SingleTarget {
        private fun writeLocal(target: BackupTarget) {
            val local = File(tempDir, target.localPath)
            if (target.isDirectory) {
                local.mkdirs()
                File(local, "deployment.yaml").writeText("apiVersion: apps/v1")
            } else {
                local.writeText("content")
            }
        }

        @ParameterizedTest(name = "backupAll stores only {0} when it is the only file present")
        @EnumSource(value = BackupTarget::class, names = ["KUBECONFIG", "K8S_MANIFESTS", "CASSANDRA_PATCH"])
        fun `backupAll stores only the target that exists`(target: BackupTarget) {
            writeLocal(target)

            val result = service.backupAll(tempDir.absolutePath, clusterState).getOrThrow()

            val stored = target.s3PathProvider(root)
            val expectedKey = if (target.isDirectory) stored.resolve("deployment.yaml").getKey() else stored.getKey()
            assertThat(result.successfulTargets).containsExactly(target)
            assertThat(storedKeys()).containsExactly(expectedKey)
            assertThat(events<Event.Backup.ConfigBackedUp>().map { it.displayName }).containsExactly(target.displayName)
        }

        @ParameterizedTest(name = "backupAll fails for {0} when no bucket is configured")
        @EnumSource(value = BackupTarget::class, names = ["KUBECONFIG", "K8S_MANIFESTS", "CASSANDRA_PATCH"])
        fun `backupAll fails and stores nothing when no bucket is configured`(target: BackupTarget) {
            writeLocal(target)

            val result = service.backupAll(tempDir.absolutePath, noBucketState)

            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(storedKeys()).isEmpty()
        }

        @ParameterizedTest(name = "restoreAll restores only {0} when it is the only object stored")
        @EnumSource(value = BackupTarget::class, names = ["KUBECONFIG", "K8S_MANIFESTS", "CASSANDRA_PATCH"])
        fun `restoreAll restores only the target that is stored`(target: BackupTarget) {
            val stored = target.s3PathProvider(root)
            if (target.isDirectory) {
                store.uploadContent("apiVersion: apps/v1", stored.resolve("deployment.yaml"))
            } else {
                store.uploadContent("content", stored)
            }

            val result = service.restoreAll(tempDir.absolutePath, clusterState).getOrThrow()

            val local = File(tempDir, target.localPath)
            val restored = if (target.isDirectory) File(local, "deployment.yaml") else local
            assertThat(result.successfulTargets).containsExactly(target)
            assertThat(tempDir.listFiles()!!.map { it.name }).containsExactly(local.name)
            assertThat(restored).exists()
            assertThat(events<Event.Backup.ConfigRestored>().map { it.displayName }).containsExactly(target.displayName)
        }

        @Test
        fun `restoreAll fails and writes nothing when no bucket is configured`() {
            val result = service.restoreAll(tempDir.absolutePath, noBucketState)

            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(tempDir.listFiles()).isEmpty()
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
