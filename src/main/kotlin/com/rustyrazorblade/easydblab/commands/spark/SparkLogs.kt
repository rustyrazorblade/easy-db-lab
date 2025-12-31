package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.SparkService
import com.rustyrazorblade.easydblab.services.VictoriaLogsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Query EMR/Spark logs from Victoria Logs.
 *
 * Logs are collected from S3 by Vector and stored in Victoria Logs on the control node.
 * This command queries Victoria Logs to display step logs.
 *
 * Usage:
 * - `spark logs` - Query logs for most recent job
 * - `spark logs --step-id s-XXXXX` - Query logs for specific job
 * - `spark logs --limit 500` - Limit number of log lines returned
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "logs",
    description = ["Query Spark/EMR logs from Victoria Logs"],
)
class SparkLogs : PicoBaseCommand() {
    private val sparkService: SparkService by inject()
    private val victoriaLogsService: VictoriaLogsService by inject()

    @Option(
        names = ["--step-id"],
        description = ["EMR step ID (defaults to most recent job)"],
    )
    var stepId: String? = null

    @Suppress("MagicNumber")
    @Option(
        names = ["--limit", "-n"],
        description = ["Maximum number of log lines to return (default: 100)"],
    )
    var limit: Int = 100

    @Option(
        names = ["--since"],
        description = ["Time range: 1h, 30m, 1d (default: 1d)"],
    )
    var since: String = "1d"

    override fun execute() {
        // Validate cluster exists and is accessible
        val clusterInfo =
            sparkService
                .validateCluster()
                .getOrElse { error ->
                    error(error.message ?: "Failed to validate EMR cluster")
                }

        // Determine step ID - use provided or get most recent
        val targetStepId =
            stepId ?: getMostRecentStepId(clusterInfo.clusterId)

        outputHandler.handleMessage("Querying logs for step: $targetStepId\n")

        // Query Victoria Logs for EMR logs matching this step ID
        val query = "source:emr AND \"$targetStepId\""
        val logs =
            victoriaLogsService
                .query(query, since, limit)
                .getOrElse { exception ->
                    outputHandler.handleError("Failed to query logs: ${exception.message}")
                    outputHandler.handleMessage(
                        """
                        |Tips:
                        |  - Ensure observability stack is deployed: easy-db-lab k8 apply
                        |  - Check if Victoria Logs is running: kubectl get pods
                        |  - Logs may take a few minutes to be ingested from S3
                        """.trimMargin(),
                    )
                    return
                }

        if (logs.isEmpty()) {
            outputHandler.handleMessage(
                """
                |No logs found for step $targetStepId
                |
                |Tips:
                |  - Logs may take a few minutes to be ingested from S3
                |  - Try increasing the time range with --since 1d
                """.trimMargin(),
            )
        } else {
            outputHandler.handleMessage(logs.joinToString("\n"))
            outputHandler.handleMessage("\nFound ${logs.size} log entries.")
        }
    }

    /**
     * Gets the step ID of the most recent job on the cluster.
     *
     * @param clusterId The EMR cluster ID
     * @return The step ID of the most recent job
     * @throws IllegalStateException if no jobs are found
     */
    private fun getMostRecentStepId(clusterId: String): String {
        val jobs =
            sparkService
                .listJobs(clusterId, limit = 1)
                .getOrElse { error ->
                    error(error.message ?: "Failed to list jobs")
                }

        if (jobs.isEmpty()) {
            error("No jobs found on cluster $clusterId")
        }

        return jobs.first().stepId
    }
}
