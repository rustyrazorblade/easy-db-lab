package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Result of a Victoria backup operation.
 *
 * @property s3Path The S3 path where the backup was stored
 * @property timestamp The timestamp of the backup
 */
data class VictoriaBackupResult(
    val s3Path: ClusterS3Path,
    val timestamp: String,
)

/**
 * Service for backing up VictoriaMetrics and VictoriaLogs data to S3.
 *
 * This service creates backups using the native Victoria tools:
 * - VictoriaMetrics: Uses vmbackup tool for native backup to S3
 * - VictoriaLogs: Creates snapshots via API and uploads to S3
 */
interface VictoriaBackupService {
    /**
     * Backs up VictoriaMetrics data to S3.
     *
     * Creates a Kubernetes Job that runs vmbackup to directly backup
     * VictoriaMetrics data to S3.
     *
     * @param controlHost The control node running VictoriaMetrics
     * @param clusterState The cluster state containing S3 bucket configuration
     * @param destinationUri Optional S3 URI to override the default destination (e.g., s3://bucket/path)
     * @return Result containing backup details or failure
     */
    fun backupMetrics(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        destinationUri: String? = null,
    ): Result<VictoriaBackupResult>

    /**
     * Backs up VictoriaLogs data to S3.
     *
     * Creates snapshots of all log partitions and uploads them to S3.
     *
     * @param controlHost The control node running VictoriaLogs
     * @param clusterState The cluster state containing S3 bucket configuration
     * @param destinationUri Optional S3 URI to override the default destination (e.g., s3://bucket/path)
     * @return Result containing backup details or failure
     */
    fun backupLogs(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        destinationUri: String? = null,
    ): Result<VictoriaBackupResult>
}

/**
 * Default implementation of VictoriaBackupService.
 *
 * Uses Kubernetes Jobs to run backup operations on the control node.
 *
 * @property k8sService Service for Kubernetes operations
 * @property socksProxyService Service for SOCKS proxy connections
 * @property outputHandler Handler for user-facing output messages
 */
class DefaultVictoriaBackupService(
    private val k8sService: K8sService,
    private val outputHandler: OutputHandler,
) : VictoriaBackupService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val NAMESPACE = "default"
        private const val JOB_TIMEOUT_SECONDS = 600 // 10 minutes
        private const val JOB_POLL_INTERVAL_MS = 5000L
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    override fun backupMetrics(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        destinationUri: String?,
    ): Result<VictoriaBackupResult> =
        runCatching {
            log.info { "Starting VictoriaMetrics backup for cluster ${clusterState.name}" }

            val region = clusterState.initConfig?.region ?: "us-west-2"
            val timestamp = generateTimestamp()

            val (bucket, s3Path) =
                if (destinationUri != null) {
                    parseS3Uri(destinationUri, timestamp)
                } else {
                    val s3Bucket =
                        clusterState.s3Bucket
                            ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
                    s3Bucket to ClusterS3Path.from(clusterState).victoriaMetrics().resolve(timestamp)
                }

            outputHandler.handleMessage("Creating VictoriaMetrics backup to ${s3Path.toUri()}...")

            // Create a Job that runs vmbackup
            val jobName = "vmbackup-${timestamp.lowercase()}"
            val jobYaml = loadBackupJobYaml("vmbackup-job.yaml", jobName, bucket, s3Path.getKey(), region)

            // Apply the job
            k8sService.createJob(controlHost, NAMESPACE, jobYaml).getOrThrow()
            log.info { "Created backup job: $jobName" }

            outputHandler.handleMessage("Backup job started: $jobName")

            // Wait for job completion
            waitForJobCompletion(controlHost, jobName)

            // Clean up the job
            k8sService.deleteJob(controlHost, NAMESPACE, jobName).getOrElse {
                log.warn { "Failed to delete backup job: ${it.message}" }
            }

            outputHandler.handleMessage("VictoriaMetrics backup completed: ${s3Path.toUri()}")

            VictoriaBackupResult(s3Path, timestamp)
        }

    override fun backupLogs(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        destinationUri: String?,
    ): Result<VictoriaBackupResult> =
        runCatching {
            log.info { "Starting VictoriaLogs backup for cluster ${clusterState.name}" }

            val region = clusterState.initConfig?.region ?: "us-west-2"
            val timestamp = generateTimestamp()

            val (bucket, s3Path) =
                if (destinationUri != null) {
                    parseS3Uri(destinationUri, timestamp)
                } else {
                    val s3Bucket =
                        clusterState.s3Bucket
                            ?: error("S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first.")
                    s3Bucket to ClusterS3Path.from(clusterState).victoriaLogs().resolve(timestamp)
                }

            outputHandler.handleMessage("Creating VictoriaLogs backup to ${s3Path.toUri()}...")

            // Create a Job that snapshots VictoriaLogs partitions and syncs to S3
            val jobName = "vlbackup-${timestamp.lowercase()}"
            val jobYaml = loadBackupJobYaml("vlbackup-job.yaml", jobName, bucket, s3Path.getKey(), region)

            // Apply the job
            k8sService.createJob(controlHost, NAMESPACE, jobYaml).getOrThrow()
            log.info { "Created backup job: $jobName" }

            outputHandler.handleMessage("Backup job started: $jobName")

            // Wait for job completion
            waitForJobCompletion(controlHost, jobName)

            // Clean up the job
            k8sService.deleteJob(controlHost, NAMESPACE, jobName).getOrElse {
                log.warn { "Failed to delete backup job: ${it.message}" }
            }

            outputHandler.handleMessage("VictoriaLogs backup completed: ${s3Path.toUri()}")

            VictoriaBackupResult(s3Path, timestamp)
        }

    private fun generateTimestamp(): String = TIMESTAMP_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC))

    /**
     * Parse an S3 URI into bucket and path components.
     *
     * @param uri The S3 URI (e.g., s3://bucket/path)
     * @param timestamp The timestamp to append to the path
     * @return Pair of bucket name and ClusterS3Path
     */
    internal fun parseS3Uri(
        uri: String,
        timestamp: String,
    ): Pair<String, ClusterS3Path> {
        require(uri.startsWith("s3://")) { "Destination must be an S3 URI (s3://bucket/path)" }
        val withoutPrefix = uri.removePrefix("s3://")
        val slashIndex = withoutPrefix.indexOf('/')

        val bucket: String
        val basePath: String
        if (slashIndex == -1) {
            bucket = withoutPrefix
            basePath = ""
        } else {
            bucket = withoutPrefix.substring(0, slashIndex)
            basePath = withoutPrefix.substring(slashIndex + 1).trimEnd('/')
        }

        val s3Path =
            if (basePath.isNotEmpty()) {
                ClusterS3Path.root(bucket).resolve(basePath).resolve(timestamp)
            } else {
                ClusterS3Path.root(bucket).resolve(timestamp)
            }

        return bucket to s3Path
    }

    private fun loadBackupJobYaml(
        resourceName: String,
        jobName: String,
        bucket: String,
        s3Key: String,
        region: String,
    ): String {
        val template =
            this::class.java
                .getResourceAsStream(resourceName)
                ?.bufferedReader()
                ?.readText()
                ?: error("Resource not found: $resourceName")

        val variables =
            mapOf(
                "JOB_NAME" to jobName,
                "S3_BUCKET" to bucket,
                "S3_KEY" to s3Key,
                "AWS_REGION" to region,
            )
        return StringSubstitutor(variables, "__", "__").replace(template)
    }

    @Suppress("MagicNumber", "NestedBlockDepth")
    private fun waitForJobCompletion(
        controlHost: ClusterHost,
        jobName: String,
    ) {
        log.info { "Waiting for backup job $jobName to complete..." }

        val startTime = System.currentTimeMillis()
        val timeoutMs = JOB_TIMEOUT_SECONDS * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val labelValue =
                if (jobName.startsWith("vm")) "victoriametrics-backup" else "victorialogs-backup"
            val jobs =
                k8sService
                    .getJobsByLabel(
                        controlHost,
                        NAMESPACE,
                        "app.kubernetes.io/name",
                        labelValue,
                    ).getOrThrow()

            val job = jobs.find { it.name == jobName }
            if (job != null) {
                when (job.status) {
                    "Completed" -> {
                        log.info { "Backup job $jobName completed successfully" }
                        return
                    }
                    "Failed" -> {
                        // Get logs for debugging
                        val pods = k8sService.getPodsForJob(controlHost, NAMESPACE, jobName).getOrElse { emptyList() }
                        val podLogs =
                            pods.firstOrNull()?.let { pod ->
                                k8sService.getPodLogs(controlHost, NAMESPACE, pod.name).getOrElse { "No logs available" }
                            } ?: "No pods found"
                        error("Backup job $jobName failed. Pod logs:\n$podLogs")
                    }
                }
            }

            Thread.sleep(JOB_POLL_INTERVAL_MS)
            outputHandler.handleMessage("Waiting for backup to complete...")
        }

        error("Backup job $jobName timed out after $JOB_TIMEOUT_SECONDS seconds")
    }
}
