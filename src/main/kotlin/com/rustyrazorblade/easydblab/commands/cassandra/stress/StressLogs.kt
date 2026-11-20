package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.services.StressJobService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * View logs from cassandra-easy-stress jobs on Kubernetes.
 *
 * Retrieves and displays logs from the pod(s) associated with a stress job.
 */
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "logs",
    description = ["View logs from stress jobs"],
)
class StressLogs : PicoBaseCommand() {
    private val stressJobService: StressJobService by inject()
    private val nodeOutput: NodeOutput by inject()

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
        // Get pods for the job
        val pods =
            stressJobService
                .getPodsForJob(jobName)
                .getOrElse { e ->
                    error("Failed to get pods for job $jobName: ${e.message}")
                }

        if (pods.isEmpty()) {
            error("No pods found for job: $jobName")
        }

        // Each pod's lines are tagged with its name, as node output is, so pods never blur together.
        for (pod in pods) {
            nodeOutput.passthrough(pod.name, "status ${pod.status}")

            val logsResult = stressJobService.getPodLogs(pod.name, tailLines)
            if (logsResult.isFailure) {
                eventBus.emit(
                    Event.Stress.PodLogFailed(
                        podName = pod.name,
                        error = logsResult.exceptionOrNull()?.message ?: "unknown error",
                    ),
                )
                continue
            }

            val logs = logsResult.getOrThrow()
            nodeOutput.passthrough(pod.name, logs.ifEmpty { "(no logs available yet)" })
        }
    }
}
