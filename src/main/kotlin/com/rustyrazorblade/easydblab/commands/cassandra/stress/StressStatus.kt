package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.StressJobService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.Duration

/**
 * Check status of cassandra-easy-stress jobs on Kubernetes.
 *
 * Lists all stress jobs with their current status, completion state, and age.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "status",
    description = ["Check status of stress jobs"],
)
class StressStatus : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Option(
        names = ["--name", "-n"],
        description = ["Filter by job name (partial match)"],
    )
    var jobName: String? = null

    override fun execute() {
        // Get control node from cluster state
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Get stress jobs
        val jobs =
            stressJobService
                .listJobs(controlNode)
                .getOrElse { e ->
                    error("Failed to get stress jobs: ${e.message}")
                }

        // Filter by name if specified
        val filteredJobs =
            if (jobName != null) {
                jobs.filter { it.name.contains(jobName!!) }
            } else {
                jobs
            }

        if (filteredJobs.isEmpty()) {
            eventBus.emit(Event.Stress.NoJobsFound)
            return
        }

        eventBus.emit(
            Event.Stress.JobStatusList(
                filteredJobs.sortedByDescending { it.age }.map { job ->
                    Event.Stress.JobStatusRow(
                        name = job.name,
                        status = job.status,
                        completions = job.completions,
                        age = formatDuration(job.age),
                    )
                },
            ),
        )
    }

    /**
     * Formats a Duration to a human-readable string (e.g., "2h", "5m", "30s").
     */
    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % Constants.Time.SECONDS_PER_MINUTE
        val seconds = duration.seconds % Constants.Time.SECONDS_PER_MINUTE

        return when {
            hours > 0 -> "${hours}h${if (minutes > 0) "${minutes}m" else ""}"
            minutes > 0 -> "${minutes}m${if (seconds > 0) "${seconds}s" else ""}"
            else -> "${seconds}s"
        }
    }
}
