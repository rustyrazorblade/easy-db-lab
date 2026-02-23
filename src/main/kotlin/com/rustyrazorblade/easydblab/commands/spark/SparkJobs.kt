package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.SparkService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * List recent Spark jobs on the EMR cluster.
 *
 * This command retrieves and displays information about recent Spark job steps
 * submitted to the EMR cluster, including step ID, name, state, and start time.
 *
 * Usage:
 * - `spark jobs` - Lists the 10 most recent jobs
 * - `spark jobs --limit 20` - Lists the 20 most recent jobs
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "jobs",
    description = ["List recent Spark jobs on the cluster"],
)
class SparkJobs : PicoBaseCommand() {
    private val sparkService: SparkService by inject()

    @Option(
        names = ["--limit"],
        description = ["Maximum number of jobs to display (default: 10)"],
    )
    var limit: Int = SparkService.DEFAULT_JOB_LIST_LIMIT

    override fun execute() {
        // Validate cluster exists and is accessible
        val clusterInfo =
            sparkService
                .validateCluster()
                .getOrElse { error ->
                    error(error.message ?: "Failed to validate EMR cluster")
                }

        eventBus.emit(Event.Message("Listing jobs for cluster: ${clusterInfo.clusterId}"))
        eventBus.emit(Event.Message(""))

        // Get job list
        val jobs =
            sparkService
                .listJobs(clusterInfo.clusterId, limit)
                .getOrElse { error ->
                    error(error.message ?: "Failed to list jobs")
                }

        if (jobs.isEmpty()) {
            eventBus.emit(Event.Message("No jobs found on cluster."))
            return
        }

        // Display jobs in a formatted table
        eventBus.emit(Event.Message(formatJobsTable(jobs)))
    }

    /**
     * Formats the job list as a table for display.
     *
     * @param jobs List of job information
     * @return Formatted table string
     */
    private fun formatJobsTable(jobs: List<SparkService.JobInfo>): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val formatString = "%-20s %-30s %-12s %-20s"

        val header = String.format(Locale.US, formatString, "STEP ID", "NAME", "STATE", "START TIME")
        val separator = "-".repeat(header.length)

        val rows =
            jobs.map { job ->
                val startTime = job.startTime?.let { formatter.format(it) } ?: "N/A"
                String.format(
                    Locale.US,
                    formatString,
                    job.stepId,
                    truncate(job.name, Constants.EMR.JOB_NAME_MAX_LENGTH),
                    job.state,
                    startTime,
                )
            }

        return buildString {
            appendLine(header)
            appendLine(separator)
            rows.forEach { appendLine(it) }
        }
    }

    /**
     * Truncates a string to the specified length, adding "..." if truncated.
     *
     * @param text The text to truncate
     * @param maxLength Maximum length
     * @return Truncated string
     */
    private fun truncate(
        text: String,
        maxLength: Int,
    ): String =
        if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength - Constants.EMR.TRUNCATION_SUFFIX_LENGTH) + "..."
        }
}
