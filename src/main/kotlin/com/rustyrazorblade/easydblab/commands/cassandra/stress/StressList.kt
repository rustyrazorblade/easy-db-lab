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
 * List available cassandra-easy-stress workloads.
 *
 * Runs cassandra-easy-stress list command as a K8s Job and displays output.
 * All arguments are passed through to cassandra-easy-stress list.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "list",
    description = ["List available cassandra-easy-stress workloads"],
)
class StressList : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val stressJobService: StressJobService by inject()

    @Option(
        names = ["--image"],
        description = ["Container image (default: ${Constants.Stress.IMAGE})"],
    )
    var image: String = Constants.Stress.IMAGE

    @Parameters(
        description = ["Additional arguments passed to cassandra-easy-stress list"],
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

        // Build args: list + any additional args
        val args = mutableListOf("list")
        args.addAll(stressArgs)

        eventBus.emit(Event.Message("Running cassandra-easy-stress list..."))

        val output =
            stressJobService
                .runCommand(controlNode, image, args)
                .getOrElse { e ->
                    error("Failed to run command: ${e.message}")
                }

        if (output.isNotEmpty()) {
            eventBus.emit(Event.Message(output))
        }
    }
}
