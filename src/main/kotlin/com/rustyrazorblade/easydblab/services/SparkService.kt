package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.events.Event
import software.amazon.awssdk.services.emr.model.StepState
import java.nio.file.Path
import java.time.Instant

/**
 * Service for managing Spark job lifecycle on EMR clusters.
 *
 * This service encapsulates all EMR Spark operations including job submission,
 * monitoring, and status checking. It provides a centralized interface for
 * Spark operations that can be used by multiple commands.
 *
 * All operations return Result types for explicit error handling following
 * the established pattern in this codebase.
 *
 * Future implementations could support alternative Spark providers such as:
 * - LocalSparkService for local development
 * - DatabricksSparkService for cloud-agnostic deployments
 */
interface SparkService {
    /**
     * Submits a Spark job to the EMR cluster.
     *
     * @param clusterId The EMR cluster ID
     * @param jarPath S3 path to the JAR file (s3://bucket/key)
     * @param mainClass Main class to execute
     * @param jobArgs Arguments to pass to the Spark application
     * @param jobName Optional job name (defaults to main class)
     * @param sparkConf Spark configuration properties (passed as --conf key=value)
     * @param envVars Environment variables (passed to both executor and app master)
     * @return Result containing the EMR step ID on success, or error on failure
     */
    fun submitJob(
        clusterId: String,
        jarPath: String,
        mainClass: String,
        jobArgs: List<String> = listOf(),
        jobName: String? = null,
        sparkConf: Map<String, String> = emptyMap(),
        envVars: Map<String, String> = emptyMap(),
    ): Result<String>

    /**
     * Waits for a Spark job to complete, polling EMR for status updates.
     *
     * This method blocks until the job reaches a terminal state (COMPLETED, FAILED, or CANCELLED).
     * It polls the EMR API periodically and provides progress updates via OutputHandler.
     *
     * @param clusterId The EMR cluster ID
     * @param stepId The EMR step ID to monitor
     * @return Result containing the final JobStatus on success, or error on failure
     */
    fun waitForJobCompletion(
        clusterId: String,
        stepId: String,
    ): Result<JobStatus>

    /**
     * Gets the current status of a Spark job.
     *
     * This is a non-blocking call that returns the current job state.
     *
     * @param clusterId The EMR cluster ID
     * @param stepId The EMR step ID
     * @return Result containing the current JobStatus, or error on failure
     */
    fun getJobStatus(
        clusterId: String,
        stepId: String,
    ): Result<JobStatus>

    /**
     * Validates that the EMR cluster exists and is in a valid state for job submission.
     *
     * Valid states are: WAITING, RUNNING
     *
     * @return Result containing EMRClusterInfo if cluster is valid, or error if not found or in invalid state
     */
    fun validateCluster(): Result<EMRClusterInfo>

    /**
     * Lists recent Spark jobs on the EMR cluster.
     *
     * @param clusterId The EMR cluster ID
     * @param limit Maximum number of jobs to return (default 10)
     * @return Result containing a list of JobInfo objects, or error on failure
     */
    fun listJobs(
        clusterId: String,
        limit: Int = DEFAULT_JOB_LIST_LIMIT,
    ): Result<List<JobInfo>>

    /**
     * Retrieves the log content for a Spark job step.
     *
     * Downloads the log file from S3, decompresses it (gzip), and returns the content.
     * EMR logs are stored at: s3://{emrLogs}/{cluster-id}/steps/{step-id}/{logType}.gz
     *
     * @param clusterId The EMR cluster ID
     * @param stepId The EMR step ID
     * @param logType The type of log to retrieve (default: STDOUT)
     * @return Result containing the log content as a String, or error on failure
     */
    fun getStepLogs(
        clusterId: String,
        stepId: String,
        logType: LogType = LogType.STDOUT,
    ): Result<String>

    /**
     * Downloads all EMR logs to a local directory organized by step ID.
     *
     * Downloads the complete log directory structure from S3 to logs/emr/{stepId}/,
     * preserving the relative path structure. This includes node logs, container logs,
     * and other EMR infrastructure logs.
     *
     * @param stepId The step ID to use for the local directory name
     * @return Result containing the local path where logs were saved, or error on failure
     */
    fun downloadAllLogs(stepId: String): Result<Path>

    /**
     * Downloads only the step-specific logs (stdout and stderr) for a Spark job.
     *
     * This is faster than downloadAllLogs() and downloads only the most relevant
     * logs for debugging Spark job failures:
     * - stdout.gz: Spark application output
     * - stderr.gz: Error messages and stack traces
     *
     * Logs are saved to logs/{clusterId}/{stepId}/ and decompressed.
     *
     * @param clusterId The EMR cluster ID
     * @param stepId The EMR step ID
     * @return Result containing the local path where logs were saved, or error on failure
     */
    fun downloadStepLogs(
        clusterId: String,
        stepId: String,
    ): Result<Path>

    /**
     * Gets detailed information about a step (equivalent to `aws emr describe-step`).
     *
     * This returns comprehensive step information including timing, configuration,
     * and failure details which is useful for debugging failed jobs.
     *
     * @param clusterId The EMR cluster ID
     * @param stepId The EMR step ID
     * @return Result containing the detailed step information, or error on failure
     */
    fun getStepDetails(
        clusterId: String,
        stepId: String,
    ): Result<StepDetails>

    /**
     * Represents the status of a Spark job.
     *
     * @property state The current EMR step state
     * @property stateChangeReason Optional reason for state change (e.g., "User request")
     * @property failureDetails Optional details if the job failed
     */
    data class JobStatus(
        val state: StepState,
        val stateChangeReason: String? = null,
        val failureDetails: String? = null,
    )

    /**
     * Information about a Spark job.
     *
     * @property stepId The EMR step ID
     * @property name The job name
     * @property state The current EMR step state
     * @property startTime When the job started (null if not yet started)
     */
    data class JobInfo(
        val stepId: String,
        val name: String,
        val state: StepState,
        val startTime: Instant?,
    )

    /**
     * Detailed step information for debugging (equivalent to `aws emr describe-step`).
     *
     * @property stepId The EMR step ID
     * @property name The step name
     * @property state The current EMR step state
     * @property stateChangeReasonCode Code indicating why state changed (e.g., "PENDING")
     * @property stateChangeReasonMessage Detailed reason for state change
     * @property failureReason Reason for failure if applicable
     * @property failureMessage Detailed failure message
     * @property failureLogFile S3 path to the failure log file
     * @property creationTime When the step was created
     * @property startTime When the step started running
     * @property endTime When the step completed
     * @property jarPath The JAR file being executed
     * @property mainClass The main class being executed
     * @property args The arguments passed to the step
     */
    data class StepDetails(
        val stepId: String,
        val name: String,
        val state: StepState,
        val stateChangeReasonCode: String?,
        val stateChangeReasonMessage: String?,
        val failureReason: String?,
        val failureMessage: String?,
        val failureLogFile: String?,
        val creationTime: Instant?,
        val startTime: Instant?,
        val endTime: Instant?,
        val jarPath: String?,
        val mainClass: String?,
        val args: List<String>,
    ) {
        /**
         * Format the step details for display.
         */
        @Suppress("CyclomaticComplexMethod")
        fun toDisplayString(): String =
            buildString {
                appendLine("=== Step Details ===")
                appendLine("Step ID: $stepId")
                appendLine("Name: $name")
                appendLine("State: $state")
                stateChangeReasonCode?.let { appendLine("State Change Reason Code: $it") }
                stateChangeReasonMessage?.let { appendLine("State Change Reason: $it") }
                appendLine()
                appendLine("=== Timeline ===")
                creationTime?.let { appendLine("Created: $it") }
                startTime?.let { appendLine("Started: $it") }
                endTime?.let { appendLine("Ended: $it") }
                if (startTime != null && endTime != null) {
                    val durationSeconds =
                        java.time.Duration
                            .between(startTime, endTime)
                            .seconds
                    appendLine("Duration: ${durationSeconds}s")
                }
                appendLine()
                appendLine("=== Configuration ===")
                jarPath?.let { appendLine("JAR: $it") }
                mainClass?.let { appendLine("Main Class: $it") }
                if (args.isNotEmpty()) {
                    appendLine("Args: ${args.joinToString(" ")}")
                }
                if (failureReason != null || failureMessage != null) {
                    appendLine()
                    appendLine("=== Failure Details ===")
                    failureReason?.let { appendLine("Reason: $it") }
                    failureMessage?.let { appendLine("Message: $it") }
                    failureLogFile?.let { appendLine("Log File: $it") }
                }
            }

        /**
         * Convert to a structured [Event.Emr.SparkStepDetails] event.
         */
        fun toEvent(): Event.Emr.SparkStepDetails {
            val durationSecs =
                if (startTime != null && endTime != null) {
                    java.time.Duration
                        .between(startTime, endTime)
                        .seconds
                } else {
                    null
                }
            return Event.Emr.SparkStepDetails(
                stepId = stepId,
                name = name,
                state = state.toString(),
                stateChangeReasonCode = stateChangeReasonCode,
                stateChangeReasonMessage = stateChangeReasonMessage,
                creationTime = creationTime?.toString(),
                startTime = startTime?.toString(),
                endTime = endTime?.toString(),
                durationSeconds = durationSecs,
                jarPath = jarPath,
                mainClass = mainClass,
                args = args,
                failureReason = failureReason,
                failureMessage = failureMessage,
                failureLogFile = failureLogFile,
            )
        }
    }

    /**
     * Types of EMR step logs available in S3.
     */
    enum class LogType(
        val filename: String,
    ) {
        STDOUT("stdout.gz"),
        STDERR("stderr.gz"),
        CONTROLLER("controller.gz"),
    }

    companion object {
        const val DEFAULT_JOB_LIST_LIMIT = 10
    }
}
