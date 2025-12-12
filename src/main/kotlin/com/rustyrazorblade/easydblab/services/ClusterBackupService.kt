package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Service for backing up and restoring cluster configuration files to/from S3.
 *
 * This service enables cluster state recovery by persisting configuration files
 * to S3, allowing users to restore their cluster when using the --vpc-id flag
 * to reconnect to an existing cluster.
 *
 * Backed up files (7 total):
 * - kubeconfig: s3://{bucket}/k3s/kubeconfig
 * - k8s manifests: s3://{bucket}/k8s/
 * - cassandra.patch.yaml: s3://{bucket}/config/cassandra.patch.yaml
 * - cassandra/ directory: s3://{bucket}/config/cassandra-config/
 * - cassandra_versions.yaml: s3://{bucket}/config/cassandra_versions.yaml
 * - environment.sh: s3://{bucket}/config/environment.sh
 * - setup_instance.sh: s3://{bucket}/config/setup_instance.sh
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

    /**
     * Performs incremental backup - only uploads files whose hashes have changed.
     * Compares current file hashes against stored hashes in ClusterState.backupHashes.
     *
     * @param workingDirectory The local working directory containing config files
     * @param clusterState The cluster state containing S3 bucket and stored hashes
     * @return Result with details of what was backed up, including updated hashes
     */
    fun backupChanged(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<IncrementalBackupResult>
}

/**
 * Result of a backup operation.
 *
 * @property successfulTargets Set of BackupTargets that were successfully backed up
 * @property filesBackedUp Total number of files backed up (including directory contents)
 */
data class BackupResult(
    val successfulTargets: Set<BackupTarget> = emptySet(),
    val filesBackedUp: Int = 0,
) {
    /** Returns true if the specified target was successfully backed up. */
    fun isBackedUp(target: BackupTarget): Boolean = target in successfulTargets

    /** Returns true if any files were backed up. */
    fun hasBackups(): Boolean = filesBackedUp > 0
}

/**
 * Result of a restore operation.
 *
 * @property successfulTargets Set of BackupTargets that were successfully restored
 * @property filesRestored Total number of files restored (including directory contents)
 */
data class RestoreResult(
    val successfulTargets: Set<BackupTarget> = emptySet(),
    val filesRestored: Int = 0,
) {
    /** Returns true if the specified target was successfully restored. */
    fun isRestored(target: BackupTarget): Boolean = target in successfulTargets

    /** Returns true if any files were restored. */
    fun hasRestores(): Boolean = filesRestored > 0
}

/**
 * Enumeration of backup targets with their local paths, S3 path mappings, and metadata.
 * This is the single source of truth for all backup/restore operations.
 *
 * @property localPath The relative path from the working directory
 * @property isDirectory True if this target is a directory, false if it's a file
 * @property s3PathProvider Lambda that returns the S3 path for this target given a root ClusterS3Path
 * @property displayName Human-readable name for logging and user output
 */
enum class BackupTarget(
    val localPath: String,
    val isDirectory: Boolean,
    val s3PathProvider: (ClusterS3Path) -> ClusterS3Path,
    val displayName: String,
) {
    KUBECONFIG("kubeconfig", false, { it.kubeconfig() }, "Kubeconfig"),
    K8S_MANIFESTS("k8s", true, { it.k8s() }, "K8s manifests"),
    CASSANDRA_PATCH("cassandra.patch.yaml", false, { it.cassandraPatch() }, "Cassandra patch"),
    CASSANDRA_CONFIG("cassandra", true, { it.cassandraConfig() }, "Cassandra config"),
    CASSANDRA_VERSIONS("cassandra_versions.yaml", false, { it.cassandraVersions() }, "Cassandra versions"),
    ENVIRONMENT_SCRIPT("environment.sh", false, { it.environmentScript() }, "Environment script"),
    SETUP_INSTANCE_SCRIPT("setup_instance.sh", false, { it.setupInstanceScript() }, "Setup instance script"),
}

/**
 * Result of an incremental backup operation.
 *
 * @property filesChecked Total number of backup targets checked
 * @property filesUploaded Number of files/directories uploaded (changed since last backup)
 * @property filesSkipped Number of files/directories skipped (unchanged)
 * @property updatedHashes Map of BackupTarget name to new SHA-256 hash for uploaded files
 */
data class IncrementalBackupResult(
    val filesChecked: Int,
    val filesUploaded: Int,
    val filesSkipped: Int,
    val updatedHashes: Map<String, String>,
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
    override fun backupAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<BackupResult> =
        runCatching {
            validateS3Bucket(clusterState)
            val s3Root = ClusterS3Path.from(clusterState)
            val successfulTargets = mutableSetOf<BackupTarget>()
            var filesBackedUp = 0

            for (target in BackupTarget.entries) {
                val localFile = File(workingDirectory, target.localPath)
                val existsAndValid =
                    if (target.isDirectory) {
                        localFile.exists() && localFile.isDirectory
                    } else {
                        localFile.exists()
                    }

                if (!existsAndValid) {
                    continue
                }

                val s3Path = target.s3PathProvider(s3Root)
                if (target.isDirectory) {
                    val uploadResult = objectStore.uploadDirectory(localFile.toPath(), s3Path, showProgress = true)
                    filesBackedUp += uploadResult.filesUploaded
                } else {
                    objectStore.uploadFile(localFile, s3Path, showProgress = true)
                    filesBackedUp++
                }
                outputHandler.handleMessage("${target.displayName} backed up to S3: ${s3Path.toUri()}")
                successfulTargets.add(target)
            }

            BackupResult(successfulTargets, filesBackedUp)
        }

    override fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult> =
        runCatching {
            validateS3Bucket(clusterState)
            val s3Root = ClusterS3Path.from(clusterState)
            val successfulTargets = mutableSetOf<BackupTarget>()
            var filesRestored = 0

            for (target in BackupTarget.entries) {
                val s3Path = target.s3PathProvider(s3Root)
                val existsInS3 =
                    if (target.isDirectory) {
                        objectStore.directoryExists(s3Path)
                    } else {
                        objectStore.fileExists(s3Path)
                    }

                if (!existsInS3) {
                    continue
                }

                val localPath = File(workingDirectory, target.localPath).toPath()
                if (target.isDirectory) {
                    objectStore.downloadDirectory(s3Path, localPath, showProgress = true)
                    val localDir = localPath.toFile()
                    if (localDir.exists()) {
                        filesRestored += localDir.walkTopDown().filter { it.isFile }.count()
                    }
                } else {
                    objectStore.downloadFile(s3Path, localPath, showProgress = true)
                    filesRestored++
                }
                outputHandler.handleMessage("${target.displayName} restored from S3: ${s3Path.toUri()}")
                successfulTargets.add(target)
            }

            RestoreResult(successfulTargets, filesRestored)
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

    override fun backupChanged(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<IncrementalBackupResult> =
        runCatching {
            validateS3Bucket(clusterState)

            val currentHashes = computeAllBackupHashes(workingDirectory)
            val storedHashes = clusterState.backupHashes
            val s3Root = ClusterS3Path.from(clusterState)

            val updatedHashes = mutableMapOf<String, String>()
            var filesUploaded = 0
            var filesSkipped = 0

            for (target in BackupTarget.entries) {
                val currentHash = currentHashes[target]
                val storedHash = storedHashes[target.name]

                // Skip if file doesn't exist
                if (currentHash == null) {
                    log.debug { "Skipping ${target.name}: file does not exist" }
                    continue
                }

                // Skip if hash hasn't changed
                if (currentHash == storedHash) {
                    log.debug { "Skipping ${target.name}: hash unchanged" }
                    filesSkipped++
                    continue
                }

                // Upload the changed file
                uploadTarget(workingDirectory, target, s3Root)
                updatedHashes[target.name] = currentHash
                filesUploaded++

                log.info { "Backed up ${target.name} (hash changed)" }
            }

            IncrementalBackupResult(
                filesChecked = BackupTarget.entries.size,
                filesUploaded = filesUploaded,
                filesSkipped = filesSkipped,
                updatedHashes = updatedHashes,
            )
        }

    /**
     * Uploads a specific backup target to S3.
     */
    private fun uploadTarget(
        workingDirectory: String,
        target: BackupTarget,
        s3Root: ClusterS3Path,
    ) {
        val localPath = File(workingDirectory, target.localPath).toPath()
        val s3Path = target.s3PathProvider(s3Root)

        if (target.isDirectory) {
            objectStore.uploadDirectory(localPath, s3Path, showProgress = false)
        } else {
            objectStore.uploadFile(localPath.toFile(), s3Path, showProgress = false)
        }
    }

    /**
     * Computes hashes for all backup targets.
     *
     * @param workingDirectory The working directory containing config files
     * @return Map of BackupTarget to SHA-256 hash (null if file doesn't exist)
     */
    private fun computeAllBackupHashes(workingDirectory: String): Map<BackupTarget, String?> =
        BackupTarget.entries.associateWith { target ->
            val file = File(workingDirectory, target.localPath)
            if (target.isDirectory) {
                computeDirectoryHash(file)
            } else {
                computeFileHash(file)
            }
        }

    /**
     * Computes SHA-256 hash of a single file.
     *
     * @param file The file to hash
     * @return Hex-encoded SHA-256 hash, or null if file doesn't exist
     */
    private fun computeFileHash(file: File): String? {
        if (!file.exists() || !file.isFile) return null

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes a deterministic SHA-256 hash of a directory.
     * The hash is based on sorted file paths and their content hashes.
     *
     * @param directory The directory to hash
     * @return Hex-encoded SHA-256 hash, or null if directory doesn't exist
     */
    private fun computeDirectoryHash(directory: File): String? {
        if (!directory.exists() || !directory.isDirectory) return null

        val digest = MessageDigest.getInstance("SHA-256")

        // Sort files for deterministic ordering
        directory
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(directory).path to computeFileHash(it) }
            .sortedBy { it.first }
            .forEach { (path, hash) ->
                digest.update(path.toByteArray())
                digest.update((hash ?: "").toByteArray())
            }

        return digest
            .digest()
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val HASH_BUFFER_SIZE = 8192
    }
}
