package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import software.amazon.awssdk.services.emr.model.StepState

/**
 * Check the status of a Spark job on the EMR cluster.
 *
 * This command retrieves the current status of a Spark job step. If no step ID
 * is provided, it defaults to checking the most recent job.
 *
 * Usage:
 * - `spark status` - Shows status of most recent job
 * - `spark status --step-id s-XXXXX` - Shows status of specific job
 * - `spark status --verbose` - Shows detailed step information (equivalent to `aws emr describe-step`)
 *
 * To view logs, use: easy-db-lab spark logs --step-id <step-id>
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Check status of a Spark job"],
)
class SparkStatus : PicoBaseCommand() {
    private val sparkService: SparkService by inject()

    @Option(
        names = ["--step-id"],
        description = ["EMR step ID (defaults to most recent job)"],
    )
    var stepId: String? = null

    @Option(
        names = ["--verbose", "-v"],
        description = ["Show detailed step information (equivalent to aws emr describe-step)"],
    )
    var verbose: Boolean = false

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

        if (verbose) {
            // Show detailed step information
            val stepDetails =
                sparkService
                    .getStepDetails(clusterInfo.clusterId, targetStepId)
                    .getOrElse { error ->
                        error(error.message ?: "Failed to get step details")
                    }
            outputHandler.handleMessage(stepDetails.toDisplayString())
        } else {
            // Show basic status
            val jobStatus =
                sparkService
                    .getJobStatus(clusterInfo.clusterId, targetStepId)
                    .getOrElse { error ->
                        error(error.message ?: "Failed to get job status")
                    }

            // Build status output
            val statusInfo =
                buildString {
                    appendLine("Step ID: $targetStepId")
                    appendLine("State: ${jobStatus.state}")
                    jobStatus.stateChangeReason?.let {
                        appendLine("Reason: $it")
                    }
                    jobStatus.failureDetails?.let {
                        appendLine("Failure Details: $it")
                    }
                }
            outputHandler.handleMessage(statusInfo.trimEnd())

            // Show log query hint for failed jobs
            if (jobStatus.state == StepState.FAILED) {
                outputHandler.handleMessage(
                    """
                    |
                    |For more details, run:
                    |  easy-db-lab spark status --step-id $targetStepId --verbose
                    |
                    |View logs with:
                    |  easy-db-lab spark logs --step-id $targetStepId
                    """.trimMargin(),
                )
            }
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
