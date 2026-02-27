package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.ActionOnFailure
import software.amazon.awssdk.services.emr.model.AddJobFlowStepsRequest
import software.amazon.awssdk.services.emr.model.DescribeStepRequest
import software.amazon.awssdk.services.emr.model.HadoopJarStepConfig
import software.amazon.awssdk.services.emr.model.ListStepsRequest
import software.amazon.awssdk.services.emr.model.StepConfig
import software.amazon.awssdk.services.emr.model.StepState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Default implementation of SparkService using AWS EMR.
 *
 * This implementation uses AWS SDK's EmrClient to interact with EMR clusters.
 * It follows the established service pattern in the codebase with Result types
 * for error handling and retry logic using RetryUtil.
 *
 * ## Retry Configuration
 * - Maximum 3 attempts with exponential backoff (1s, 2s, 4s)
 * - Retries only on 5xx server errors (via RetryUtil)
 *
 * ## Polling Behavior
 * When waiting for job completion, this service uses a blocking poll loop:
 * - Poll interval: 5 seconds (configurable via Constants.EMR.POLL_INTERVAL_MS)
 * - Maximum wait time: 4 hours (configurable via Constants.EMR.MAX_POLL_TIMEOUT_MS)
 * - Status logging: Every 60 seconds (12 polls) to reduce output noise
 * - The polling is blocking - it holds the thread until job completion or timeout
 *
 * @property emrClient AWS EMR client for API operations
 * @property clusterStateManager Manager for cluster state persistence
 */
class EMRSparkService(
    private val emrClient: EmrClient,
    private val objectStore: ObjectStore,
    private val clusterStateManager: ClusterStateManager,
    private val victoriaLogsService: VictoriaLogsService,
    private val eventBus: EventBus,
) : SparkService {
    private val log = KotlinLogging.logger {}

    companion object {
        private val VALID_CLUSTER_STATES = setOf("WAITING", "RUNNING")
        private val TERMINAL_JOB_STATES = setOf(StepState.COMPLETED, StepState.FAILED, StepState.CANCELLED)
    }

    override fun submitJob(
        clusterId: String,
        jarPath: String,
        mainClass: String,
        jobArgs: List<String>,
        jobName: String?,
        sparkConf: Map<String, String>,
        envVars: Map<String, String>,
    ): Result<String> =
        runCatching {
            val stepName = jobName ?: mainClass.split(".").last()

            // Enrich spark conf and env vars with OTel Java agent and Pyroscope configuration
            val otelSparkConf = buildOtelSparkConf(sparkConf, stepName)
            val otelEnvVars = buildOtelEnvVars(envVars, stepName)

            val hadoopJarStep =
                HadoopJarStepConfig
                    .builder()
                    .jar(Constants.EMR.COMMAND_RUNNER_JAR)
                    .args(buildSparkSubmitArgs(jarPath, mainClass, jobArgs, otelSparkConf, otelEnvVars))
                    .build()

            val stepConfig =
                StepConfig
                    .builder()
                    .name(stepName)
                    .actionOnFailure(ActionOnFailure.CONTINUE)
                    .hadoopJarStep(hadoopJarStep)
                    .build()

            val request =
                AddJobFlowStepsRequest
                    .builder()
                    .jobFlowId(clusterId)
                    .steps(stepConfig)
                    .build()

            val stepId =
                executeWithRetry("emr-submit-job") {
                    val response = emrClient.addJobFlowSteps(request)
                    val stepIds = response.stepIds()
                    require(stepIds.isNotEmpty()) {
                        "EMR returned no step IDs for submitted job"
                    }
                    stepIds.first()
                }

            log.info { "Submitted Spark job: $stepId to cluster $clusterId" }
            stepId
        }

    override fun waitForJobCompletion(
        clusterId: String,
        stepId: String,
    ): Result<SparkService.JobStatus> =
        runCatching {
            eventBus.emit(Event.Emr.SparkJobWaiting)

            val startTime = System.currentTimeMillis()
            var currentStatus: SparkService.JobStatus
            var pollCount = 0

            do {
                Thread.sleep(Constants.EMR.POLL_INTERVAL_MS)
                pollCount++

                // Check for timeout
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > Constants.EMR.MAX_POLL_TIMEOUT_MS) {
                    val timeoutMinutes = Constants.EMR.MAX_POLL_TIMEOUT_MS / Constants.Time.MILLIS_PER_MINUTE
                    error("Job polling timed out after $timeoutMinutes minutes")
                }

                val statusResult = getJobStatus(clusterId, stepId)
                currentStatus = statusResult.getOrThrow()

                // Log less frequently to reduce noise (every 60 seconds at default 5s interval)
                if (pollCount % Constants.EMR.LOG_INTERVAL_POLLS == 0) {
                    eventBus.emit(Event.Emr.SparkJobStateUpdate(currentStatus.state.toString()))
                }
            } while (currentStatus.state !in TERMINAL_JOB_STATES)

            when (currentStatus.state) {
                StepState.COMPLETED -> {
                    eventBus.emit(Event.Emr.SparkJobCompleted)
                    currentStatus
                }
                StepState.FAILED -> handleFailedJob(clusterId, stepId, currentStatus)
                StepState.CANCELLED -> {
                    val errorMessage = "Job was cancelled"
                    log.warn { errorMessage }
                    error(errorMessage)
                }
                else -> {
                    val errorMessage = "Job ended in unexpected state: ${currentStatus.state}"
                    log.error { errorMessage }
                    error(errorMessage)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun handleFailedJob(
        clusterId: String,
        stepId: String,
        currentStatus: SparkService.JobStatus,
    ): Nothing {
        val s3Bucket = clusterStateManager.load().s3Bucket ?: "UNKNOWN_BUCKET"
        val emrLogsPath = "${Constants.EMR.S3_LOG_PREFIX}$clusterId/steps/$stepId/"

        displayFailureDetails(clusterId, stepId, currentStatus)
        queryVictoriaLogs(stepId)
        downloadAndDisplayLogs(clusterId, stepId, s3Bucket, emrLogsPath)

        val errorMessage = "Job failed: ${currentStatus.failureDetails ?: "Unknown reason"}"
        log.error { errorMessage }
        error(errorMessage)
    }

    private fun displayFailureDetails(
        clusterId: String,
        stepId: String,
        currentStatus: SparkService.JobStatus,
    ) {
        val stepDetails = getStepDetails(clusterId, stepId).getOrNull()
        if (stepDetails != null) {
            eventBus.emit(stepDetails.toEvent())
        } else {
            eventBus.emit(
                Event.Emr.SparkStepError(
                    """
                    |=== Job Failed ===
                    |Step ID: $stepId
                    |Failure: ${currentStatus.failureDetails ?: "Unknown reason"}
                    """.trimMargin(),
                ),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun queryVictoriaLogs(stepId: String) {
        eventBus.emit(Event.Emr.SparkLogHeader)
        try {
            Thread.sleep(Constants.EMR.LOG_INGESTION_WAIT_MS)

            // Query using OTel Java agent log attributes (service.name) instead of defunct step_id tag
            // The Java agent tags logs with service.name=spark-<job-name>
            val query = """service.name:~"spark-.*""""

            victoriaLogsService
                .query(query = query, timeRange = "1h", limit = Constants.EMR.MAX_LOG_LINES)
                .onSuccess { logs ->
                    if (logs.isEmpty()) {
                        eventBus.emit(Event.Emr.SparkNoLogsInstruction(stepId))
                    } else {
                        logs.forEach { eventBus.emit(Event.Emr.SparkLogLine(it)) }
                        eventBus.emit(Event.Emr.SparkLogCount(logs.size))
                    }
                }.onFailure { e ->
                    eventBus.emit(Event.Emr.SparkLogError(e.message ?: "Unknown error"))
                    eventBus.emit(Event.Emr.SparkLogInstruction(stepId))
                }
        } catch (e: Exception) {
            log.warn { "Failed to query Victoria Logs: ${e.message}" }
            eventBus.emit(Event.Emr.SparkLogQueryFailed)
            eventBus.emit(Event.Emr.SparkLogInstruction(stepId))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun downloadAndDisplayLogs(
        clusterId: String,
        stepId: String,
        s3Bucket: String,
        emrLogsPath: String,
    ) {
        val manualDebugCommands =
            """
            |
            |=== Manual Debug Commands ===
            |  # Check step details
            |  aws emr describe-step --cluster-id $clusterId --step-id $stepId
            |
            |  # View stderr directly from S3
            |  aws s3 cp s3://$s3Bucket/${emrLogsPath}stderr.gz - | gunzip
            """.trimMargin()

        eventBus.emit(Event.Emr.DownloadingStepLogsHeader)
        try {
            downloadStepLogs(clusterId, stepId)
                .onFailure { e ->
                    eventBus.emit(Event.Emr.SparkLogDownloadFailed(e.message ?: "Unknown error"))
                    eventBus.emit(Event.Emr.SparkDebugInstructions(manualDebugCommands))
                }
        } catch (e: Exception) {
            log.warn { "Failed to download step logs: ${e.message}" }
            eventBus.emit(Event.Emr.SparkDebugInstructions(manualDebugCommands))
        }
    }

    override fun getJobStatus(
        clusterId: String,
        stepId: String,
    ): Result<SparkService.JobStatus> =
        runCatching {
            val describeRequest =
                DescribeStepRequest
                    .builder()
                    .clusterId(clusterId)
                    .stepId(stepId)
                    .build()

            executeWithRetry("emr-describe-step") {
                val response = emrClient.describeStep(describeRequest)
                val status = response.step().status()

                SparkService.JobStatus(
                    state = status.state(),
                    stateChangeReason = status.stateChangeReason()?.message(),
                    failureDetails = status.failureDetails()?.message(),
                )
            }
        }

    override fun getStepDetails(
        clusterId: String,
        stepId: String,
    ): Result<SparkService.StepDetails> =
        runCatching {
            val describeRequest =
                DescribeStepRequest
                    .builder()
                    .clusterId(clusterId)
                    .stepId(stepId)
                    .build()

            executeWithRetry("emr-describe-step-details") {
                val response = emrClient.describeStep(describeRequest)
                val step = response.step()
                val status = step.status()
                val hadoopJarConfig = step.config()

                // Extract main class and args from the command line args
                // Format: spark-submit --class MainClass [--conf ...] s3://bucket/jar.jar [app args]
                val allArgs = hadoopJarConfig?.args() ?: emptyList<String>()
                val mainClass = extractMainClass(allArgs)
                val jarPath = extractJarPath(allArgs)
                val appArgs = extractAppArgs(allArgs)

                SparkService.StepDetails(
                    stepId = step.id(),
                    name = step.name(),
                    state = status.state(),
                    stateChangeReasonCode = status.stateChangeReason()?.code()?.toString(),
                    stateChangeReasonMessage = status.stateChangeReason()?.message(),
                    failureReason = status.failureDetails()?.reason(),
                    failureMessage = status.failureDetails()?.message(),
                    failureLogFile = status.failureDetails()?.logFile(),
                    creationTime = status.timeline()?.creationDateTime(),
                    startTime = status.timeline()?.startDateTime(),
                    endTime = status.timeline()?.endDateTime(),
                    jarPath = jarPath,
                    mainClass = mainClass,
                    args = appArgs,
                )
            }
        }

    /**
     * Extracts the main class from spark-submit arguments.
     * Looks for --class followed by the class name.
     */
    private fun extractMainClass(args: List<String>): String? {
        val classIndex = args.indexOf("--class")
        return if (classIndex >= 0 && classIndex + 1 < args.size) {
            args[classIndex + 1]
        } else {
            null
        }
    }

    /**
     * Extracts the JAR path from spark-submit arguments.
     * The JAR is typically the first s3:// argument after all options.
     */
    private fun extractJarPath(args: List<String>): String? = args.find { it.startsWith("s3://") && it.endsWith(".jar") }

    /**
     * Extracts application arguments (args after the JAR file).
     */
    private fun extractAppArgs(args: List<String>): List<String> {
        val jarIndex = args.indexOfFirst { it.startsWith("s3://") && it.endsWith(".jar") }
        return if (jarIndex >= 0 && jarIndex + 1 < args.size) {
            args.drop(jarIndex + 1)
        } else {
            emptyList()
        }
    }

    override fun validateCluster(): Result<EMRClusterInfo> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val emrCluster =
                clusterState.emrCluster
                    ?: error(
                        "No EMR cluster found in cluster state. Use --spark.enable during init to create an EMR cluster.",
                    )

            val clusterInfo =
                EMRClusterInfo(
                    clusterId = emrCluster.clusterId,
                    name = emrCluster.clusterName,
                    masterPublicDns = emrCluster.masterPublicDns,
                    state = emrCluster.state,
                )

            check(clusterInfo.state in VALID_CLUSTER_STATES) {
                "EMR cluster is in state '${clusterInfo.state}'. Expected one of: $VALID_CLUSTER_STATES"
            }

            log.info { "Validated EMR cluster ${clusterInfo.clusterId} in state ${clusterInfo.state}" }
            clusterInfo
        }

    override fun listJobs(
        clusterId: String,
        limit: Int,
    ): Result<List<SparkService.JobInfo>> =
        runCatching {
            val listRequest =
                ListStepsRequest
                    .builder()
                    .clusterId(clusterId)
                    .build()

            executeWithRetry("emr-list-steps") {
                val response = emrClient.listSteps(listRequest)
                response
                    .steps()
                    .take(limit)
                    .map { step ->
                        SparkService.JobInfo(
                            stepId = step.id(),
                            name = step.name(),
                            state = step.status().state(),
                            startTime = step.status().timeline()?.startDateTime(),
                        )
                    }
            }
        }

    override fun getStepLogs(
        clusterId: String,
        stepId: String,
        logType: SparkService.LogType,
    ): Result<String> =
        runCatching {
            // Build the S3 path for logs: {emrLogs}/{cluster-id}/steps/{step-id}/{logType}.gz
            val clusterState = clusterStateManager.load()
            val s3Path = clusterState.s3Path()
            val logPath =
                s3Path
                    .emrLogs()
                    .resolve(clusterId)
                    .resolve("steps")
                    .resolve(stepId)
                    .resolve(logType.filename)

            // Create local logs directory: ./logs/{cluster-id}/{step-id}/
            val localLogsDir = Paths.get("logs", clusterId, stepId)
            Files.createDirectories(localLogsDir)

            val localGzFile = localLogsDir.resolve(logType.filename)
            val localLogFile = localLogsDir.resolve(logType.filename.removeSuffix(".gz"))

            eventBus.emit(Event.Emr.SparkLogDownloadStart(logPath.toUri().toString()))
            eventBus.emit(Event.Emr.SparkLogDownloadSaveTo(localLogFile.toString()))

            // Download with retry (logs may not be immediately available)
            executeS3LogRetrievalWithRetry("s3-download-logs") {
                objectStore.downloadFile(logPath, localGzFile, showProgress = false)
            }

            // Decompress to final location
            decompressGzipFile(localGzFile, localLogFile)

            // Read and return content
            Files.readString(localLogFile)
        }

    override fun downloadAllLogs(stepId: String): Result<Path> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val s3Path = clusterState.s3Path()
            val emrLogsPath = s3Path.emrLogs()

            // Save to logs/emr/<step-id>/
            val localLogsDir = Paths.get("logs", "emr", stepId)
            Files.createDirectories(localLogsDir)

            eventBus.emit(Event.Emr.EmrLogsDownloading(emrLogsPath.toUri().toString()))
            eventBus.emit(Event.Emr.EmrLogsSaveTo(localLogsDir.toString()))

            objectStore.downloadDirectory(emrLogsPath, localLogsDir, showProgress = true)

            localLogsDir
        }

    override fun downloadStepLogs(
        clusterId: String,
        stepId: String,
    ): Result<Path> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val s3Path = clusterState.s3Path()

            // Create local logs directory: ./logs/{cluster-id}/{step-id}/
            val localLogsDir = Paths.get("logs", clusterId, stepId)
            Files.createDirectories(localLogsDir)

            eventBus.emit(Event.Emr.StepLogsDownloading(localLogsDir.toString()))

            // Download both stdout and stderr
            var logsDownloaded = 0
            for (logType in listOf(SparkService.LogType.STDOUT, SparkService.LogType.STDERR)) {
                val logPath =
                    s3Path
                        .emrLogs()
                        .resolve(clusterId)
                        .resolve("steps")
                        .resolve(stepId)
                        .resolve(logType.filename)

                val localGzFile = localLogsDir.resolve(logType.filename)
                val localLogFile = localLogsDir.resolve(logType.filename.removeSuffix(".gz"))

                try {
                    executeS3LogRetrievalWithRetry("s3-download-${logType.name.lowercase()}") {
                        objectStore.downloadFile(logPath, localGzFile, showProgress = false)
                    }
                    decompressGzipFile(localGzFile, localLogFile)
                    // Remove the .gz file after decompression
                    Files.deleteIfExists(localGzFile)
                    eventBus.emit(Event.Emr.SparkLogDownloadComplete(logType.name.lowercase()))
                    logsDownloaded++
                } catch (e: Exception) {
                    log.warn { "Could not download ${logType.filename}: ${e.message}" }
                    eventBus.emit(Event.Emr.SparkLogDownloadUnavailable(logType.name.lowercase()))
                }
            }

            if (logsDownloaded == 0) {
                val s3Bucket = clusterState.s3Bucket ?: "UNKNOWN_BUCKET"
                val emrLogsPath = "${Constants.EMR.S3_LOG_PREFIX}$clusterId/steps/$stepId/"
                eventBus.emit(
                    Event.Emr.SparkDebugInstructions(
                        """
                        |
                        |No logs were available. EMR logs typically take 30-60 seconds to upload after job completion.
                        |
                        |=== Manual Debug Commands ===
                        |  # Retry log download
                        |  easy-db-lab spark logs --step-id $stepId
                        |
                        |  # Check step details
                        |  aws emr describe-step --cluster-id $clusterId --step-id $stepId
                        |
                        |  # View stderr directly from S3 (once available)
                        |  aws s3 cp s3://$s3Bucket/${emrLogsPath}stderr.gz - | gunzip
                        """.trimMargin(),
                    ),
                )
            }

            // Display stderr content if it exists (most useful for debugging)
            val stderrFile = localLogsDir.resolve("stderr")
            if (Files.exists(stderrFile)) {
                val stderrContent = Files.readString(stderrFile)
                if (stderrContent.isNotBlank()) {
                    eventBus.emit(Event.Emr.SparkStderrHeader(Constants.EMR.STDERR_TAIL_LINES))
                    val lines = stderrContent.lines()
                    val lastLines =
                        if (lines.size > Constants.EMR.STDERR_TAIL_LINES) {
                            lines.takeLast(Constants.EMR.STDERR_TAIL_LINES)
                        } else {
                            lines
                        }
                    lastLines.forEach { eventBus.emit(Event.Emr.SparkStderrLine(it)) }
                    eventBus.emit(Event.Emr.SparkStderrFooter)
                }
            }

            localLogsDir
        }

    /**
     * Decompresses a gzip file to a specified output file.
     *
     * @param gzipFile Path to the gzip file
     * @param outputFile Path to write the decompressed content
     */
    private fun decompressGzipFile(
        gzipFile: Path,
        outputFile: Path,
    ) {
        GZIPInputStream(Files.newInputStream(gzipFile)).use { gzipInput ->
            Files.newOutputStream(outputFile).use { output ->
                gzipInput.copyTo(output)
            }
        }
    }

    /**
     * Returns sparkConf as-is. We no longer override extraJavaOptions at submission time
     * because it replaces the spark-defaults.conf value (which contains -javaagent flags).
     * Per-job Pyroscope app name is set via PYROSCOPE_APPLICATION_NAME env var instead.
     */
    private fun buildOtelSparkConf(
        sparkConf: Map<String, String>,
        @Suppress("unused") jobName: String,
    ): Map<String, String> = sparkConf

    /**
     * Overrides the OTel service name and Pyroscope application name for per-job attribution.
     * Exporter config and -javaagent flags come from spark-defaults classification at cluster level.
     */
    private fun buildOtelEnvVars(
        envVars: Map<String, String>,
        jobName: String,
    ): Map<String, String> {
        val otelVars =
            mapOf(
                "OTEL_SERVICE_NAME" to "spark-$jobName",
                "PYROSCOPE_APPLICATION_NAME" to "spark-$jobName",
            )

        return otelVars + envVars
    }

    /**
     * Builds the spark-submit command arguments for EMR.
     *
     * @param jarPath S3 path to the JAR file
     * @param mainClass Main class to execute
     * @param jobArgs Application arguments
     * @param sparkConf Spark configuration properties
     * @param envVars Environment variables for executor and app master
     * @return List of command-line arguments for spark-submit
     */
    private fun buildSparkSubmitArgs(
        jarPath: String,
        mainClass: String,
        jobArgs: List<String>,
        sparkConf: Map<String, String>,
        envVars: Map<String, String>,
    ): List<String> {
        val args = mutableListOf(Constants.EMR.SPARK_SUBMIT_COMMAND)

        // Add Spark configuration properties via --conf
        // spark-submit sets these as system properties, which SparkConf(true) picks up
        sparkConf.forEach { (key, value) ->
            args.add("--conf")
            args.add("$key=$value")
        }

        // Add environment variables (to driver, executor, and app master)
        envVars.forEach { (key, value) ->
            args.add("--conf")
            args.add("spark.driverEnv.$key=$value")
            args.add("--conf")
            args.add("spark.executorEnv.$key=$value")
            args.add("--conf")
            args.add("spark.yarn.appMasterEnv.$key=$value")
        }

        args.add("--class")
        args.add(mainClass)
        args.add(jarPath)
        args.addAll(jobArgs)

        return args
    }

    /**
     * Executes an EMR API operation with retry logic.
     *
     * Uses exponential backoff (1s, 2s, 4s) with up to 3 attempts.
     * Retries only on 5xx server errors.
     *
     * @param operationName Name for the retry instance (for logging/metrics)
     * @param operation The EMR API operation to execute
     * @return The result of the operation
     */
    private fun <T> executeWithRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = RetryUtil.createAwsRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }

    /**
     * Executes an S3 log retrieval operation with retry logic.
     *
     * EMR logs may not be immediately available after job completion.
     * Uses fixed 3-second delay with up to 10 attempts (~30s total wait).
     * Retries on NoSuchKeyException (404) since logs may not be uploaded yet.
     *
     * @param operationName Name for the retry instance (for logging/metrics)
     * @param operation The S3 operation to execute
     * @return The result of the operation
     */
    private fun <T> executeS3LogRetrievalWithRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = RetryUtil.createS3LogRetrievalRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }
}
