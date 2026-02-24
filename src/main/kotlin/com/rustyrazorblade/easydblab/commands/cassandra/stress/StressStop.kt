package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.StressJobService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Stop and delete cassandra-easy-stress jobs from Kubernetes.
 *
 * Deletes the specified job and its associated ConfigMap (if any).
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "stop",
    description = ["Stop and delete stress jobs"],
)
class StressStop : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Parameters(
        index = "0",
        description = ["Job name to stop"],
        arity = "0..1",
    )
    var jobName: String? = null

    @Option(
        names = ["--all"],
        description = ["Delete all stress jobs"],
    )
    var deleteAll: Boolean = false

    @Option(
        names = ["--force", "-f"],
        description = ["Force deletion without confirmation"],
    )
    var force: Boolean = false

    override fun execute() {
        // Get control node from cluster state
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        if (!deleteAll && jobName == null) {
            error("Please specify a job name or use --all to delete all stress jobs.")
        }

        if (deleteAll) {
            // Get all stress jobs
            val jobs =
                stressJobService
                    .listJobs(controlNode)
                    .getOrElse { e ->
                        error("Failed to get stress jobs: ${e.message}")
                    }

            if (jobs.isEmpty()) {
                eventBus.emit(Event.Stress.NoJobsFound)
                return
            }

            eventBus.emit(
                Event.Stress.JobsToDelete(
                    jobs.map { job ->
                        Event.Stress.JobSummary(name = job.name, status = job.status)
                    },
                ),
            )

            if (!force) {
                eventBus.emit(Event.Stress.DeleteConfirmRequired)
                return
            }

            // Delete all jobs and their ConfigMaps
            var deleted = 0
            for (job in jobs) {
                deleteJobAndConfigMap(controlNode, job.name)
                deleted++
            }

            eventBus.emit(Event.Stress.BulkDeleted(deleted))
        } else {
            // Delete specific job
            val name = jobName!!
            deleteJobAndConfigMap(controlNode, name)
            eventBus.emit(Event.Stress.SingleDeleted(name))
        }
    }

    /**
     * Deletes a job and its associated ConfigMap (if any).
     */
    private fun deleteJobAndConfigMap(
        controlNode: ClusterHost,
        name: String,
    ) {
        stressJobService
            .stopJob(controlNode, name)
            .getOrElse { e ->
                error("Failed to delete job $name: ${e.message}")
            }
    }
}
