package com.rustyrazorblade.easydblab.commands.cassandra.stress

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
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
@RequireProfileSetup
@RequiresProxy
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
        // Build args: list + any additional args
        val args = mutableListOf("list")
        args.addAll(stressArgs)

        eventBus.emit(Event.Stress.CommandRunning("list"))

        val output =
            stressJobService
                .runCommand(image, args)
                .getOrElse { e ->
                    error("Failed to run command: ${e.message}")
                }

        if (output.isNotEmpty()) {
            eventBus.emit(Event.Stress.CommandOutput(output))
        }
    }
}
