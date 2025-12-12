package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
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
 * @property kubeconfigBackedUp Whether kubeconfig was successfully backed up
 * @property k8sManifestsBackedUp Whether k8s manifests were successfully backed up
 * @property cassandraPatchBackedUp Whether cassandra.patch.yaml was successfully backed up
 * @property cassandraConfigBackedUp Whether cassandra/ directory was successfully backed up
 * @property cassandraVersionsBackedUp Whether cassandra_versions.yaml was successfully backed up
 * @property environmentScriptBackedUp Whether environment.sh was successfully backed up
 * @property setupInstanceScriptBackedUp Whether setup_instance.sh was successfully backed up
 * @property filesBackedUp Total number of files backed up
 */
data class BackupResult(
    val kubeconfigBackedUp: Boolean = false,
    val k8sManifestsBackedUp: Boolean = false,
    val cassandraPatchBackedUp: Boolean = false,
    val cassandraConfigBackedUp: Boolean = false,
    val cassandraVersionsBackedUp: Boolean = false,
    val environmentScriptBackedUp: Boolean = false,
    val setupInstanceScriptBackedUp: Boolean = false,
    val filesBackedUp: Int = 0,
)

/**
 * Result of a restore operation.
 *
 * @property kubeconfigRestored Whether kubeconfig was successfully restored
 * @property k8sManifestsRestored Whether k8s manifests were successfully restored
 * @property cassandraPatchRestored Whether cassandra.patch.yaml was successfully restored
 * @property cassandraConfigRestored Whether cassandra/ directory was successfully restored
 * @property cassandraVersionsRestored Whether cassandra_versions.yaml was successfully restored
 * @property environmentScriptRestored Whether environment.sh was successfully restored
 * @property setupInstanceScriptRestored Whether setup_instance.sh was successfully restored
 * @property filesRestored Total number of files restored
 */
data class RestoreResult(
    val kubeconfigRestored: Boolean = false,
    val k8sManifestsRestored: Boolean = false,
    val cassandraPatchRestored: Boolean = false,
    val cassandraConfigRestored: Boolean = false,
    val cassandraVersionsRestored: Boolean = false,
    val environmentScriptRestored: Boolean = false,
    val setupInstanceScriptRestored: Boolean = false,
    val filesRestored: Int = 0,
)

/**
 * Enumeration of backup targets with their local paths and type information.
 * Used for incremental backup to track which files have changed.
 *
 * @property localPath The relative path from the working directory
 * @property isDirectory True if this target is a directory, false if it's a file
 */
enum class BackupTarget(
    val localPath: String,
    val isDirectory: Boolean,
) {
    KUBECONFIG("kubeconfig", false),
    K8S_MANIFESTS("k8s", true),
    CASSANDRA_PATCH("cassandra.patch.yaml", false),
    CASSANDRA_CONFIG("cassandra", true),
    CASSANDRA_VERSIONS("cassandra_versions.yaml", false),
    ENVIRONMENT_SCRIPT("environment.sh", false),
    SETUP_INSTANCE_SCRIPT("setup_instance.sh", false),
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

            var kubeconfigBackedUp = false
            var k8sManifestsBackedUp = false
            var cassandraPatchBackedUp = false
            var cassandraConfigBackedUp = false
            var cassandraVersionsBackedUp = false
            var environmentScriptBackedUp = false
            var setupInstanceScriptBackedUp = false
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
                val result = objectStore.uploadDirectory(k8sDir, s3Root.k8s(), showProgress = true)
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

            // Backup cassandra/ directory
            val cassandraConfigDir = File(workingDirectory, CASSANDRA_CONFIG_LOCAL_DIR).toPath()
            if (cassandraConfigDir.toFile().exists() && cassandraConfigDir.toFile().isDirectory) {
                val s3Path = s3Root.cassandraConfig()
                val result = objectStore.uploadDirectory(cassandraConfigDir, s3Path, showProgress = true)
                cassandraConfigBackedUp = true
                filesBackedUp += result.filesUploaded
                outputHandler.handleMessage("Cassandra config backed up to S3: ${s3Path.toUri()}")
            }

            // Backup cassandra_versions.yaml
            val cassandraVersionsPath = File(workingDirectory, CASSANDRA_VERSIONS_LOCAL_FILE).toPath()
            if (cassandraVersionsPath.toFile().exists()) {
                val s3Path = s3Root.cassandraVersions()
                objectStore.uploadFile(cassandraVersionsPath.toFile(), s3Path, showProgress = true)
                cassandraVersionsBackedUp = true
                filesBackedUp++
                outputHandler.handleMessage("Cassandra versions backed up to S3: ${s3Path.toUri()}")
            }

            // Backup environment.sh
            val environmentPath = File(workingDirectory, ENVIRONMENT_LOCAL_FILE).toPath()
            if (environmentPath.toFile().exists()) {
                val s3Path = s3Root.environmentScript()
                objectStore.uploadFile(environmentPath.toFile(), s3Path, showProgress = true)
                environmentScriptBackedUp = true
                filesBackedUp++
                outputHandler.handleMessage("Environment script backed up to S3: ${s3Path.toUri()}")
            }

            // Backup setup_instance.sh
            val setupInstancePath = File(workingDirectory, SETUP_INSTANCE_LOCAL_FILE).toPath()
            if (setupInstancePath.toFile().exists()) {
                val s3Path = s3Root.setupInstanceScript()
                objectStore.uploadFile(setupInstancePath.toFile(), s3Path, showProgress = true)
                setupInstanceScriptBackedUp = true
                filesBackedUp++
                outputHandler.handleMessage("Setup instance script backed up to S3: ${s3Path.toUri()}")
            }

            BackupResult(
                kubeconfigBackedUp = kubeconfigBackedUp,
                k8sManifestsBackedUp = k8sManifestsBackedUp,
                cassandraPatchBackedUp = cassandraPatchBackedUp,
                cassandraConfigBackedUp = cassandraConfigBackedUp,
                cassandraVersionsBackedUp = cassandraVersionsBackedUp,
                environmentScriptBackedUp = environmentScriptBackedUp,
                setupInstanceScriptBackedUp = setupInstanceScriptBackedUp,
                filesBackedUp = filesBackedUp,
            )
        }

    override fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult> =
        runCatching {
            validateS3Bucket(clusterState)
            val s3Root = ClusterS3Path.from(clusterState)

            var kubeconfigRestored = false
            var k8sManifestsRestored = false
            var cassandraPatchRestored = false
            var cassandraConfigRestored = false
            var cassandraVersionsRestored = false
            var environmentScriptRestored = false
            var setupInstanceScriptRestored = false
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

            // Restore cassandra/ directory
            if (cassandraConfigExistsInS3(clusterState)) {
                val cassandraConfigDir = File(workingDirectory, CASSANDRA_CONFIG_LOCAL_DIR).toPath()
                val s3Path = s3Root.cassandraConfig()
                objectStore.downloadDirectory(s3Path, cassandraConfigDir, showProgress = true)
                cassandraConfigRestored = true
                val configDirFile = cassandraConfigDir.toFile()
                if (configDirFile.exists()) {
                    filesRestored += configDirFile.walkTopDown().filter { it.isFile }.count()
                }
                outputHandler.handleMessage("Cassandra config restored from S3: ${s3Path.toUri()}")
            }

            // Restore cassandra_versions.yaml
            if (cassandraVersionsExistsInS3(clusterState)) {
                val cassandraVersionsPath = File(workingDirectory, CASSANDRA_VERSIONS_LOCAL_FILE).toPath()
                val s3Path = s3Root.cassandraVersions()
                objectStore.downloadFile(s3Path, cassandraVersionsPath, showProgress = true)
                cassandraVersionsRestored = true
                filesRestored++
                outputHandler.handleMessage("Cassandra versions restored from S3: ${s3Path.toUri()}")
            }

            // Restore environment.sh
            if (environmentScriptExistsInS3(clusterState)) {
                val environmentPath = File(workingDirectory, ENVIRONMENT_LOCAL_FILE).toPath()
                val s3Path = s3Root.environmentScript()
                objectStore.downloadFile(s3Path, environmentPath, showProgress = true)
                environmentScriptRestored = true
                filesRestored++
                outputHandler.handleMessage("Environment script restored from S3: ${s3Path.toUri()}")
            }

            // Restore setup_instance.sh
            if (setupInstanceScriptExistsInS3(clusterState)) {
                val setupInstancePath = File(workingDirectory, SETUP_INSTANCE_LOCAL_FILE).toPath()
                val s3Path = s3Root.setupInstanceScript()
                objectStore.downloadFile(s3Path, setupInstancePath, showProgress = true)
                setupInstanceScriptRestored = true
                filesRestored++
                outputHandler.handleMessage("Setup instance script restored from S3: ${s3Path.toUri()}")
            }

            RestoreResult(
                kubeconfigRestored = kubeconfigRestored,
                k8sManifestsRestored = k8sManifestsRestored,
                cassandraPatchRestored = cassandraPatchRestored,
                cassandraConfigRestored = cassandraConfigRestored,
                cassandraVersionsRestored = cassandraVersionsRestored,
                environmentScriptRestored = environmentScriptRestored,
                setupInstanceScriptRestored = setupInstanceScriptRestored,
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

    private fun cassandraConfigExistsInS3(clusterState: ClusterState): Boolean = fileExistsInS3(clusterState) { it.cassandraConfig() }

    private fun cassandraVersionsExistsInS3(clusterState: ClusterState): Boolean = fileExistsInS3(clusterState) { it.cassandraVersions() }

    private fun environmentScriptExistsInS3(clusterState: ClusterState): Boolean = fileExistsInS3(clusterState) { it.environmentScript() }

    private fun setupInstanceScriptExistsInS3(clusterState: ClusterState): Boolean =
        fileExistsInS3(clusterState) { it.setupInstanceScript() }

    private fun fileExistsInS3(
        clusterState: ClusterState,
        pathProvider: (ClusterS3Path) -> ClusterS3Path,
    ): Boolean {
        if (clusterState.s3Bucket == null) {
            return false
        }
        val s3Path = pathProvider(ClusterS3Path.from(clusterState))
        return objectStore.fileExists(s3Path) || objectStore.directoryExists(s3Path)
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

        when (target) {
            BackupTarget.KUBECONFIG -> {
                objectStore.uploadFile(localPath.toFile(), s3Root.kubeconfig(), showProgress = false)
            }
            BackupTarget.K8S_MANIFESTS -> {
                objectStore.uploadDirectory(localPath, s3Root.k8s(), showProgress = false)
            }
            BackupTarget.CASSANDRA_PATCH -> {
                objectStore.uploadFile(localPath.toFile(), s3Root.cassandraPatch(), showProgress = false)
            }
            BackupTarget.CASSANDRA_CONFIG -> {
                objectStore.uploadDirectory(localPath, s3Root.cassandraConfig(), showProgress = false)
            }
            BackupTarget.CASSANDRA_VERSIONS -> {
                objectStore.uploadFile(localPath.toFile(), s3Root.cassandraVersions(), showProgress = false)
            }
            BackupTarget.ENVIRONMENT_SCRIPT -> {
                objectStore.uploadFile(localPath.toFile(), s3Root.environmentScript(), showProgress = false)
            }
            BackupTarget.SETUP_INSTANCE_SCRIPT -> {
                objectStore.uploadFile(localPath.toFile(), s3Root.setupInstanceScript(), showProgress = false)
            }
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
        private const val CASSANDRA_CONFIG_LOCAL_DIR = "cassandra"
        private const val CASSANDRA_VERSIONS_LOCAL_FILE = "cassandra_versions.yaml"
        private const val ENVIRONMENT_LOCAL_FILE = "environment.sh"
        private const val SETUP_INSTANCE_LOCAL_FILE = "setup_instance.sh"
        private const val HASH_BUFFER_SIZE = 8192
    }
}
