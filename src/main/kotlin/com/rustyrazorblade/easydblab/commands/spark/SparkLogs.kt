package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.LogQl
import com.rustyrazorblade.easydblab.services.LokiQueryService
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Query EMR/Spark logs from Loki.
 *
 * Logs are stored in Loki on the control node. This command queries Loki, scoped to the current
 * cluster, to display step logs.
 *
 * Usage:
 * - `spark logs` - Query logs for most recent job
 * - `spark logs --step-id s-XXXXX` - Query logs for specific job
 * - `spark logs --limit 500` - Limit number of log lines returned
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "logs",
    description = ["Query Spark/EMR logs from Loki"],
)
class SparkLogs : PicoBaseCommand() {
    private val sparkService: SparkService by inject()
    private val lokiQueryService: LokiQueryService by inject()

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

        // The given step, or the most recent one; its name is the job's, which names its logs
        val (targetStepId, stepName) =
            stepId?.let { it to getStepName(clusterInfo.clusterId, it) } ?: getMostRecentStep(clusterInfo.clusterId)

        eventBus.emit(Event.Emr.QueryingStepLogs(targetStepId))

        val query = LogQl.sparkStep(clusterState.clusterLabelName(), stepName)
        val logs =
            lokiQueryService
                .query(query, since, limit)
                .getOrElse { exception ->
                    eventBus.emit(Event.Emr.StepQueryFailed(exception.message ?: "Unknown error"))
                    eventBus.emit(
                        Event.Emr.StepQueryTips(
                            """
                        |Tips:
                        |  - Ensure the observability stack is deployed: easy-db-lab grafana update-config
                        |  - Check that Loki is running: kubectl get pods -l app.kubernetes.io/name=loki
                        |  - Logs may take a few minutes to be ingested from S3
                            """.trimMargin(),
                        ),
                    )
                    return
                }

        if (logs.isEmpty()) {
            eventBus.emit(Event.Emr.StepNoLogsFound(targetStepId))
        } else {
            println(logs.joinToString("\n"))
            println("\nFound ${logs.size} log entries.")
        }
    }

    /**
     * Gets the name of a step on the cluster.
     *
     * @throws IllegalStateException if the step cannot be described
     */
    private fun getStepName(
        clusterId: String,
        stepId: String,
    ): String =
        sparkService
            .getStepDetails(clusterId, stepId)
            .getOrElse { error ->
                error(error.message ?: "Failed to describe step $stepId")
            }.name

    /**
     * Gets the ID and name of the most recent job on the cluster.
     *
     * @param clusterId The EMR cluster ID
     * @return The step ID and name of the most recent job
     * @throws IllegalStateException if no jobs are found
     */
    private fun getMostRecentStep(clusterId: String): Pair<String, String> {
        val jobs =
            sparkService
                .listJobs(clusterId, limit = 1)
                .getOrElse { error ->
                    error(error.message ?: "Failed to list jobs")
                }

        if (jobs.isEmpty()) {
            error("No jobs found on cluster $clusterId")
        }

        return jobs.first().let { it.stepId to it.name }
    }
}
