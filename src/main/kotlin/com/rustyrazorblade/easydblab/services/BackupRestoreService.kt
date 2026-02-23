package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Unified service for backup and restore operations.
 *
 * This service coordinates:
 * - Configuration file backup/restore to/from S3 (via ClusterBackupService)
 * - VPC-based restore by looking up the S3 bucket from VPC tags and downloading all state
 *
 * It serves as the single entry point for all backup/restore workflows,
 * ensuring consistent handling of file operations.
 */
interface BackupRestoreService {
    /**
     * Restores cluster state from a VPC ID.
     *
     * This performs a complete restoration:
     * 1. Looks up S3 bucket from VPC tags
     * 2. Downloads all backed-up configuration files from S3 (including state.json)
     * 3. Loads the restored state.json
     *
     * @param vpcId The VPC ID to restore from
     * @param workingDirectory The local directory to restore files to
     * @param force If true, overwrites existing state.json
     * @return Result containing the restoration details
     */
    fun restoreFromVpc(
        vpcId: VpcId,
        workingDirectory: String,
        force: Boolean = false,
    ): Result<VpcRestoreResult>

    /**
     * Backs up all cluster configuration files to S3.
     *
     * @param workingDirectory The local directory containing config files
     * @param clusterState The cluster state with S3 bucket configuration
     * @return Result containing backup details
     */
    fun backupAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<BackupResult>

    /**
     * Performs incremental backup - only uploads files that have changed.
     *
     * @param workingDirectory The local directory containing config files
     * @param clusterState The cluster state with S3 bucket and stored hashes
     * @return Result containing incremental backup details
     */
    fun backupChanged(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<IncrementalBackupResult>

    /**
     * Restores all configuration files from S3.
     *
     * @param workingDirectory The local directory to restore files to
     * @param clusterState The cluster state with S3 bucket configuration
     * @return Result containing restore details
     */
    fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult>
}

/**
 * Result of restoring from a VPC ID.
 *
 * @property clusterState The restored cluster state
 * @property restoreResult The file restoration result
 */
data class VpcRestoreResult(
    val clusterState: ClusterState,
    val restoreResult: RestoreResult?,
)

/**
 * Default implementation of BackupRestoreService.
 *
 * Coordinates VpcService and ClusterBackupService to provide
 * unified backup/restore operations.
 */
class DefaultBackupRestoreService(
    private val vpcService: VpcService,
    private val clusterBackupService: ClusterBackupService,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
) : BackupRestoreService {
    override fun restoreFromVpc(
        vpcId: VpcId,
        workingDirectory: String,
        force: Boolean,
    ): Result<VpcRestoreResult> =
        runCatching {
            // Check if state.json already exists
            if (clusterStateManager.exists() && !force) {
                error(
                    "state.json already exists. Use --force to overwrite, or remove the file manually.\n" +
                        "Warning: Overwriting will lose any local configuration.",
                )
            }

            // Step 1: Look up cluster info and S3 bucket from VPC tags
            log.info { "Looking up cluster info from VPC: $vpcId" }
            eventBus.emit(Event.Backup.ClusterLookup(vpcId.toString()))

            val vpcTags = vpcService.getVpcTags(vpcId)
            val clusterId =
                vpcTags[CLUSTER_ID_TAG_KEY]
                    ?: error("VPC $vpcId is missing the ClusterId tag. This VPC was not created by easy-db-lab.")
            val clusterName = vpcTags["Name"] ?: "recovered-cluster"
            val s3Bucket =
                vpcTags[Constants.Vpc.BUCKET_TAG_KEY]
                    ?: error("VPC $vpcId has no '${Constants.Vpc.BUCKET_TAG_KEY}' tag — cannot restore without S3 bucket.")

            eventBus.emit(Event.Backup.ClusterFound(clusterName, s3Bucket))

            // Step 2: Build minimal ClusterState to bootstrap the S3 restore
            val bootstrapState =
                ClusterState(
                    name = clusterName,
                    clusterId = clusterId,
                    vpcId = vpcId,
                    s3Bucket = s3Bucket,
                    infrastructureStatus = InfrastructureStatus.UP,
                    versions = null,
                )

            // Step 3: Download all files from S3 (including state.json)
            eventBus.emit(Event.Backup.RestoreStarting)
            val result = clusterBackupService.restoreAll(workingDirectory, bootstrapState).getOrThrow()

            if (result.hasRestores()) {
                eventBus.emit(
                    Event.Backup.RestoreComplete(
                        result.successfulTargets.map { it.displayName },
                    ),
                )
            } else {
                eventBus.emit(Event.Backup.RestoreEmpty)
            }

            // Step 4: Load the restored state.json (the real one from S3)
            val restoredState =
                if (clusterStateManager.exists()) {
                    val state = clusterStateManager.load()
                    eventBus.emit(
                        Event.Backup.StateRestored(
                            clusterName = state.name,
                            clusterId = state.clusterId,
                            hostCount = state.hosts.values.sumOf { it.size },
                        ),
                    )
                    state
                } else {
                    log.warn { "state.json was not found in S3 backup, using bootstrap state" }
                    clusterStateManager.save(bootstrapState)
                    bootstrapState
                }

            VpcRestoreResult(restoredState, result)
        }

    override fun backupAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<BackupResult> = clusterBackupService.backupAll(workingDirectory, clusterState)

    override fun backupChanged(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<IncrementalBackupResult> = clusterBackupService.backupChanged(workingDirectory, clusterState)

    override fun restoreAll(
        workingDirectory: String,
        clusterState: ClusterState,
    ): Result<RestoreResult> = clusterBackupService.restoreAll(workingDirectory, clusterState)

    companion object {
        private val log = KotlinLogging.logger {}
        private const val CLUSTER_ID_TAG_KEY = "ClusterId"
    }
}
