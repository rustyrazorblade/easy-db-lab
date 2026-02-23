package com.rustyrazorblade.easydblab.commands.cassandra.stress

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
import picocli.CommandLine.Parameters

/**
 * View logs from cassandra-easy-stress jobs on Kubernetes.
 *
 * Retrieves and displays logs from the pod(s) associated with a stress job.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "logs",
    description = ["View logs from stress jobs"],
)
class StressLogs : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Parameters(
        index = "0",
        description = ["Job name"],
    )
    lateinit var jobName: String

    @Option(
        names = ["--tail"],
        description = ["Number of lines to show from the end"],
    )
    var tailLines: Int? = null

    override fun execute() {
        // Get control node from cluster state
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Get pods for the job
        val pods =
            stressJobService
                .getPodsForJob(controlNode, jobName)
                .getOrElse { e ->
                    error("Failed to get pods for job $jobName: ${e.message}")
                }

        if (pods.isEmpty()) {
            error("No pods found for job: $jobName")
        }

        // Get logs from each pod
        for (pod in pods) {
            if (pods.size > 1) {
                eventBus.emit(Event.Message("=== Pod: ${pod.name} (${pod.status}) ==="))
            }

            val logsResult = stressJobService.getPodLogs(controlNode, pod.name, tailLines)
            if (logsResult.isFailure) {
                eventBus.emit(Event.Error("Failed to get logs for pod ${pod.name}: ${logsResult.exceptionOrNull()?.message}"))
                continue
            }

            val logs = logsResult.getOrThrow()
            if (logs.isEmpty()) {
                eventBus.emit(Event.Message("(no logs available yet)"))
            } else {
                eventBus.emit(Event.Message(logs))
            }

            if (pods.size > 1) {
                eventBus.emit(Event.Message(""))
            }
        }
    }
}
