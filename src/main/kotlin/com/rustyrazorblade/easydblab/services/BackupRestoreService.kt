package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Unified service for backup and restore operations.
 *
 * This service coordinates:
 * - State reconstruction from AWS resources (via StateReconstructionService)
 * - Configuration file backup/restore to/from S3 (via ClusterBackupService)
 *
 * It serves as the single entry point for all backup/restore workflows,
 * ensuring consistent handling of both AWS resource discovery and file operations.
 */
interface BackupRestoreService {
    /**
     * Restores cluster state from a VPC ID.
     *
     * This performs a complete restoration:
     * 1. Reconstructs ClusterState from AWS resources (VPC, EC2 instances, S3 bucket)
     * 2. Saves the reconstructed state to state.json
     * 3. Downloads all backed-up configuration files from S3
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
 * @property clusterState The reconstructed cluster state
 * @property restoreResult The file restoration result (null if no S3 bucket configured)
 */
data class VpcRestoreResult(
    val clusterState: ClusterState,
    val restoreResult: RestoreResult?,
)

/**
 * Default implementation of BackupRestoreService.
 *
 * Coordinates StateReconstructionService and ClusterBackupService to provide
 * unified backup/restore operations.
 */
class DefaultBackupRestoreService(
    private val stateReconstructionService: StateReconstructionService,
    private val clusterBackupService: ClusterBackupService,
    private val clusterStateManager: ClusterStateManager,
    private val outputHandler: OutputHandler,
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

            // Step 1: Reconstruct state from AWS resources
            log.info { "Reconstructing cluster state from VPC: $vpcId" }
            outputHandler.handleMessage("Reconstructing state from VPC: $vpcId")

            val reconstructedState = stateReconstructionService.reconstructFromVpc(vpcId)
            clusterStateManager.save(reconstructedState)

            outputHandler.handleMessage("State reconstructed successfully:")
            outputHandler.handleMessage("  Cluster name: ${reconstructedState.name}")
            outputHandler.handleMessage("  Cluster ID: ${reconstructedState.clusterId}")
            outputHandler.handleMessage("  Hosts: ${reconstructedState.hosts.values.sumOf { it.size }}")
            outputHandler.handleMessage("  S3 bucket: ${reconstructedState.s3Bucket ?: "not found"}")

            // Step 2: Restore configuration files from S3
            val restoreResult =
                if (reconstructedState.s3Bucket != null) {
                    outputHandler.handleMessage("Restoring cluster configuration from S3...")
                    val result = clusterBackupService.restoreAll(workingDirectory, reconstructedState).getOrThrow()

                    if (result.hasRestores()) {
                        outputHandler.handleMessage("Configuration restored from S3:")
                        for (target in result.successfulTargets) {
                            outputHandler.handleMessage("  - ${target.displayName}")
                        }
                    } else {
                        outputHandler.handleMessage("No configuration files found in S3 to restore")
                    }
                    result
                } else {
                    log.info { "No S3 bucket configured, skipping file restoration" }
                    null
                }

            VpcRestoreResult(reconstructedState, restoreResult)
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
    }
}
