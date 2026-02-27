package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File

/**
 * Submit a Spark job to the provisioned EMR cluster.
 *
 * This command submits JAR-based Spark applications to the EMR cluster that was created during
 * environment initialization. It supports both S3 JAR paths and local JAR files (which are
 * automatically uploaded to S3).
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "submit",
    description = ["Submit Spark job to EMR cluster"],
)
class SparkSubmit : PicoBaseCommand() {
    private val sparkService: SparkService by inject()
    private val objectStore: ObjectStore by inject()

    @Option(
        names = ["--jar"],
        description = ["Path to JAR file (local path or s3://bucket/key)"],
        required = true,
    )
    lateinit var jarPath: String

    @Option(
        names = ["--main-class"],
        description = ["Main class to execute"],
        required = true,
    )
    lateinit var mainClass: String

    @Option(
        names = ["--args"],
        description = ["Arguments to pass to the Spark application"],
        arity = "0..*",
    )
    var jobArgs: List<String> = listOf()

    @Option(
        names = ["--wait"],
        description = ["Wait for job completion"],
    )
    var wait: Boolean = false

    @Option(
        names = ["--name"],
        description = ["Job name (defaults to main class)"],
    )
    var jobName: String? = null

    @Option(
        names = ["--conf"],
        description = ["Spark configuration (key=value), can be repeated"],
        arity = "0..*",
    )
    var sparkConf: List<String> = listOf()

    @Option(
        names = ["--env"],
        description = ["Environment variable (KEY=value), can be repeated"],
        arity = "0..*",
    )
    var envVars: List<String> = listOf()

    override fun execute() {
        // Validate cluster exists and is in valid state
        val clusterInfo =
            sparkService
                .validateCluster()
                .getOrElse { error ->
                    error(error.message ?: "Failed to validate EMR cluster")
                }

        // Determine JAR location (S3 or local)
        val s3JarPath =
            if (jarPath.startsWith("s3://")) {
                eventBus.emit(Event.Emr.UsingS3Jar(jarPath))
                jarPath
            } else {
                uploadJarToS3(jarPath)
            }

        // Parse --conf options into map
        val sparkConfMap =
            sparkConf.associate { conf ->
                val parts = conf.split("=", limit = 2)
                require(parts.size == 2) { "Invalid --conf format: $conf (expected key=value)" }
                parts[0] to parts[1]
            }

        // Parse --env options into map
        val envVarsMap =
            envVars.associate { env ->
                val parts = env.split("=", limit = 2)
                require(parts.size == 2) { "Invalid --env format: $env (expected KEY=value)" }
                parts[0] to parts[1]
            }

        // Submit job to EMR
        val stepId =
            sparkService
                .submitJob(
                    clusterId = clusterInfo.clusterId,
                    jarPath = s3JarPath,
                    mainClass = mainClass,
                    jobArgs = jobArgs,
                    jobName = jobName,
                    sparkConf = sparkConfMap,
                    envVars = envVarsMap,
                ).getOrElse { exception ->
                    error(exception.message ?: "Failed to submit Spark job")
                }

        eventBus.emit(Event.Emr.JobSubmitted(stepId, clusterInfo.clusterId))

        // Optionally wait for completion
        if (wait) {
            sparkService
                .waitForJobCompletion(clusterInfo.clusterId, stepId)
                .getOrElse { exception ->
                    error(exception.message ?: "Job failed")
                }
        } else {
            eventBus.emit(Event.Emr.JobSubmittedCheckStatus(stepId))
        }
    }

    private fun uploadJarToS3(localPath: String): String {
        val localFile = File(localPath)
        require(localFile.exists()) { "JAR file does not exist: $localPath" }
        require(localPath.endsWith(".jar")) { "File must have .jar extension: $localPath" }

        // Upload to the per-cluster data bucket under spark/
        val clusterState = clusterStateManager.load()
        val jarS3Path = ClusterS3Path.root(clusterState.dataBucket).resolve("spark").resolve(localFile.name)

        // Upload using ObjectStore (handles retry logic and progress)
        val result = objectStore.uploadFile(localFile, jarS3Path, showProgress = true)

        return result.remotePath.toString()
    }
}
