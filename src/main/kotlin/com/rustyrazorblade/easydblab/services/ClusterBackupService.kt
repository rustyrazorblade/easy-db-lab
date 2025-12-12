package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path

/**
 * Service for backing up and restoring cluster configuration files to/from S3.
 *
 * This service enables cluster state recovery by persisting configuration files
 * (kubeconfig, k8s manifests, cassandra config) to S3, allowing users to restore
 * their cluster when using the --vpc-id flag to reconnect to an existing cluster.
 *
 * Backed up files:
 * - kubeconfig: s3://{bucket}/k3s/kubeconfig
 * - k8s manifests: s3://{bucket}/k8s/
 * - cassandra.patch.yaml: s3://{bucket}/config/cassandra.patch.yaml
 */
interface ClusterBackupService {
    /**
     * Backs up all cluster configuration to S3.
     *
     * @param workingDirectory The local working directory containing config files
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun backupAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<BackupResult>

    /**
     * Restores all cluster configuration from S3.
     *
     * @param workingDirectory The local working directory to restore files to
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult>

    /**
     * Backs up the kubeconfig file to S3.
     *
     * @param localPath Path to the local kubeconfig file
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun backupKubeconfig(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Restores the kubeconfig file from S3.
     *
     * @param localPath Path where the kubeconfig should be written
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun restoreKubeconfig(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Backs up the k8s manifests directory to S3.
     *
     * @param localDir Path to the local k8s directory
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun backupK8sManifests(
        localDir: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Restores the k8s manifests directory from S3.
     *
     * @param localDir Path where the k8s manifests should be restored
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun restoreK8sManifests(
        localDir: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Checks if kubeconfig exists in S3 for this cluster.
     *
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return true if kubeconfig exists in S3, false otherwise
     */
    fun kubeconfigExistsInS3(clusterState: ClusterState): Boolean

    /**
     * Checks if k8s manifests exist in S3 for this cluster.
     *
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return true if k8s manifests exist in S3, false otherwise
     */
    fun k8sManifestsExistInS3(clusterState: ClusterState): Boolean

    /**
     * Backs up the cassandra.patch.yaml file to S3.
     *
     * @param localPath Path to the local cassandra.patch.yaml file
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun backupCassandraPatch(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Restores the cassandra.patch.yaml file from S3.
     *
     * @param localPath Path where cassandra.patch.yaml should be written
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return Result indicating success or failure
     */
    fun restoreCassandraPatch(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit>

    /**
     * Checks if cassandra.patch.yaml exists in S3 for this cluster.
     *
     * @param clusterState The cluster state containing S3 bucket configuration
     * @return true if cassandra.patch.yaml exists in S3, false otherwise
     */
    fun cassandraPatchExistsInS3(clusterState: ClusterState): Boolean
}

/**
 * Result of a backup operation.
 *
 * @property kubeconfigBackedUp Whether kubeconfig was successfully backed up
 * @property k8sManifestsBackedUp Whether k8s manifests were successfully backed up
 * @property cassandraPatchBackedUp Whether cassandra.patch.yaml was successfully backed up
 * @property filesBackedUp Total number of files backed up
 */
data class BackupResult(
    val kubeconfigBackedUp: Boolean = false,
    val k8sManifestsBackedUp: Boolean = false,
    val cassandraPatchBackedUp: Boolean = false,
    val filesBackedUp: Int = 0,
)

/**
 * Result of a restore operation.
 *
 * @property kubeconfigRestored Whether kubeconfig was successfully restored
 * @property k8sManifestsRestored Whether k8s manifests were successfully restored
 * @property cassandraPatchRestored Whether cassandra.patch.yaml was successfully restored
 * @property filesRestored Total number of files restored
 */
data class RestoreResult(
    val kubeconfigRestored: Boolean = false,
    val k8sManifestsRestored: Boolean = false,
    val cassandraPatchRestored: Boolean = false,
    val filesRestored: Int = 0,
)

/**
 * Default implementation of ClusterBackupService using ObjectStore.
 *
 * @property objectStore The cloud storage service for S3 operations
 * @property outputHandler Handler for user-facing output messages
 */
class DefaultClusterBackupService(
    private val objectStore: ObjectStore,
    private val outputHandler: OutputHandler,
) : ClusterBackupService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun backupAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<BackupResult> =
        runCatching {
            validateS3Bucket(clusterState)

            var kubeconfigBackedUp = false
            var k8sManifestsBackedUp = false
            var cassandraPatchBackedUp = false
            var filesBackedUp = 0

            // Backup kubeconfig
            val kubeconfigPath = File(workingDirectory, Constants.K3s.LOCAL_KUBECONFIG).toPath()
            if (kubeconfigPath.toFile().exists()) {
                backupKubeconfig(kubeconfigPath, clusterState).getOrThrow()
                kubeconfigBackedUp = true
                filesBackedUp++
            }

            // Backup k8s manifests
            val k8sDir = File(workingDirectory, Constants.K8s.MANIFEST_DIR).toPath()
            if (k8sDir.toFile().exists() && k8sDir.toFile().isDirectory) {
                val result =
                    objectStore.uploadDirectory(
                        k8sDir,
                        ClusterS3Path.from(clusterState).k8s(),
                        showProgress = true,
                    )
                k8sManifestsBackedUp = true
                filesBackedUp += result.filesUploaded
            }

            // Backup cassandra.patch.yaml
            val cassandraPatchPath = File(workingDirectory, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).toPath()
            if (cassandraPatchPath.toFile().exists()) {
                backupCassandraPatch(cassandraPatchPath, clusterState).getOrThrow()
                cassandraPatchBackedUp = true
                filesBackedUp++
            }

            BackupResult(
                kubeconfigBackedUp = kubeconfigBackedUp,
                k8sManifestsBackedUp = k8sManifestsBackedUp,
                cassandraPatchBackedUp = cassandraPatchBackedUp,
                filesBackedUp = filesBackedUp,
            )
        }

    override fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult> =
        runCatching {
            validateS3Bucket(clusterState)

            var kubeconfigRestored = false
            var k8sManifestsRestored = false
            var cassandraPatchRestored = false
            var filesRestored = 0

            // Restore kubeconfig
            if (kubeconfigExistsInS3(clusterState)) {
                val kubeconfigPath = File(workingDirectory, Constants.K3s.LOCAL_KUBECONFIG).toPath()
                restoreKubeconfig(kubeconfigPath, clusterState).getOrThrow()
                kubeconfigRestored = true
                filesRestored++
            }

            // Restore k8s manifests
            if (k8sManifestsExistInS3(clusterState)) {
                val k8sDir = File(workingDirectory, Constants.K8s.MANIFEST_DIR).toPath()
                restoreK8sManifests(k8sDir, clusterState).getOrThrow()
                k8sManifestsRestored = true
                // Count restored files
                val k8sDirFile = k8sDir.toFile()
                if (k8sDirFile.exists()) {
                    filesRestored += k8sDirFile.walkTopDown().filter { it.isFile }.count()
                }
            }

            // Restore cassandra.patch.yaml
            if (cassandraPatchExistsInS3(clusterState)) {
                val cassandraPatchPath = File(workingDirectory, Constants.ConfigPaths.CASSANDRA_PATCH_FILE).toPath()
                restoreCassandraPatch(cassandraPatchPath, clusterState).getOrThrow()
                cassandraPatchRestored = true
                filesRestored++
            }

            RestoreResult(
                kubeconfigRestored = kubeconfigRestored,
                k8sManifestsRestored = k8sManifestsRestored,
                cassandraPatchRestored = cassandraPatchRestored,
                filesRestored = filesRestored,
            )
        }

    override fun backupKubeconfig(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val localFile = localPath.toFile()
            require(localFile.exists()) {
                "Kubeconfig file does not exist at: $localPath"
            }

            val s3Path = ClusterS3Path.from(clusterState).kubeconfig()

            log.info { "Backing up kubeconfig to S3: ${s3Path.toUri()}" }
            objectStore.uploadFile(localFile, s3Path, showProgress = true)

            outputHandler.handleMessage("Kubeconfig backed up to S3: ${s3Path.toUri()}")
        }

    override fun restoreKubeconfig(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val s3Path = ClusterS3Path.from(clusterState).kubeconfig()

            require(objectStore.fileExists(s3Path)) {
                "Kubeconfig does not exist in S3 at: ${s3Path.toUri()}"
            }

            log.info { "Restoring kubeconfig from S3: ${s3Path.toUri()}" }
            objectStore.downloadFile(s3Path, localPath, showProgress = true)

            outputHandler.handleMessage("Kubeconfig restored from S3: ${s3Path.toUri()}")
        }

    override fun backupK8sManifests(
        localDir: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val localDirFile = localDir.toFile()
            require(localDirFile.exists() && localDirFile.isDirectory) {
                "K8s manifests directory does not exist at: $localDir"
            }

            val s3Path = ClusterS3Path.from(clusterState).k8s()

            log.info { "Backing up k8s manifests to S3: ${s3Path.toUri()}" }
            objectStore.uploadDirectory(localDir, s3Path, showProgress = true)

            outputHandler.handleMessage("K8s manifests backed up to S3: ${s3Path.toUri()}")
        }

    override fun restoreK8sManifests(
        localDir: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val s3Path = ClusterS3Path.from(clusterState).k8s()

            require(objectStore.directoryExists(s3Path)) {
                "K8s manifests do not exist in S3 at: ${s3Path.toUri()}"
            }

            log.info { "Restoring k8s manifests from S3: ${s3Path.toUri()}" }
            objectStore.downloadDirectory(s3Path, localDir, showProgress = true)

            outputHandler.handleMessage("K8s manifests restored from S3: ${s3Path.toUri()}")
        }

    override fun kubeconfigExistsInS3(clusterState: ClusterState): Boolean {
        if (clusterState.s3Bucket == null) {
            log.debug { "S3 bucket not configured, kubeconfig cannot exist in S3" }
            return false
        }

        val s3Path = ClusterS3Path.from(clusterState).kubeconfig()
        return objectStore.fileExists(s3Path)
    }

    override fun k8sManifestsExistInS3(clusterState: ClusterState): Boolean {
        if (clusterState.s3Bucket == null) {
            log.debug { "S3 bucket not configured, k8s manifests cannot exist in S3" }
            return false
        }

        val s3Path = ClusterS3Path.from(clusterState).k8s()
        return objectStore.directoryExists(s3Path)
    }

    override fun backupCassandraPatch(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val localFile = localPath.toFile()
            require(localFile.exists()) {
                "Cassandra patch file does not exist at: $localPath"
            }

            val s3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            log.info { "Backing up cassandra.patch.yaml to S3: ${s3Path.toUri()}" }
            objectStore.uploadFile(localFile, s3Path, showProgress = true)

            outputHandler.handleMessage("Cassandra patch backed up to S3: ${s3Path.toUri()}")
        }

    override fun restoreCassandraPatch(
        localPath: Path,
        clusterState: ClusterState,
    ): Result<Unit> =
        runCatching {
            validateS3Bucket(clusterState)

            val s3Path = ClusterS3Path.from(clusterState).cassandraPatch()

            require(objectStore.fileExists(s3Path)) {
                "Cassandra patch does not exist in S3 at: ${s3Path.toUri()}"
            }

            log.info { "Restoring cassandra.patch.yaml from S3: ${s3Path.toUri()}" }
            objectStore.downloadFile(s3Path, localPath, showProgress = true)

            outputHandler.handleMessage("Cassandra patch restored from S3: ${s3Path.toUri()}")
        }

    override fun cassandraPatchExistsInS3(clusterState: ClusterState): Boolean {
        if (clusterState.s3Bucket == null) {
            log.debug { "S3 bucket not configured, cassandra patch cannot exist in S3" }
            return false
        }

        val s3Path = ClusterS3Path.from(clusterState).cassandraPatch()
        return objectStore.fileExists(s3Path)
    }

    private fun validateS3Bucket(clusterState: ClusterState) {
        requireNotNull(clusterState.s3Bucket) {
            "S3 bucket not configured for cluster '${clusterState.name}'"
        }
    }
}
