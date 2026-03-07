package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Cancel a running or pending Spark job on the EMR cluster.
 *
 * This command cancels an EMR step using the CancelSteps API with TERMINATE_PROCESS
 * strategy. If no step ID is provided, it cancels the most recent job.
 *
 * Usage:
 * - `spark stop` - Cancels the most recent job
 * - `spark stop --step-id s-XXXXX` - Cancels a specific job
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Cancel a running or pending Spark job"],
)
class SparkStop : PicoBaseCommand() {
    private val sparkService: SparkService by inject()

    @Option(
        names = ["--step-id"],
        description = ["EMR step ID (defaults to most recent job)"],
    )
    var stepId: String? = null

    override fun execute() {
        val clusterInfo =
            sparkService
                .validateCluster()
                .getOrElse { error ->
                    error(error.message ?: "Failed to validate EMR cluster")
                }

        val targetStepId =
            stepId ?: getMostRecentStepId(clusterInfo.clusterId)

        eventBus.emit(Event.Emr.JobCancelling(targetStepId))

        val result =
            sparkService
                .cancelJob(clusterInfo.clusterId, targetStepId)
                .getOrElse { error ->
                    error(error.message ?: "Failed to cancel job")
                }

        eventBus.emit(Event.Emr.JobCancelled(result.stepId, result.status, result.reason))
    }

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
