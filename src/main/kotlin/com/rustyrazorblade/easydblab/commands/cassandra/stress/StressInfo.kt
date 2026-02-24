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
import picocli.CommandLine.Parameters

/**
 * Show detailed information about a cassandra-easy-stress workload.
 *
 * Runs cassandra-easy-stress info command as a K8s Job and displays output.
 * All arguments are passed through to cassandra-easy-stress info.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "info",
    description = ["Show information about a cassandra-easy-stress workload"],
)
class StressInfo : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Option(
        names = ["--image"],
        description = ["Container image (default: ${Constants.Stress.IMAGE})"],
    )
    var image: String = Constants.Stress.IMAGE

    @Parameters(
        description = ["Workload name and additional arguments passed to cassandra-easy-stress info"],
        arity = "0..*",
    )
    var stressArgs: List<String> = emptyList()

    override fun execute() {
        // Get control node from cluster state
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Build args: info + any additional args
        val args = mutableListOf("info")
        args.addAll(stressArgs)

        eventBus.emit(Event.Stress.CommandRunning("info"))

        val output =
            stressJobService
                .runCommand(controlNode, image, args)
                .getOrElse { e ->
                    error("Failed to run command: ${e.message}")
                }

        if (output.isNotEmpty()) {
            eventBus.emit(Event.Stress.CommandOutput(output))
        }
    }
}
