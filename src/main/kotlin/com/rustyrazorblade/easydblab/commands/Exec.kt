package com.rustyrazorblade.easydblab.commands

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.converters.PicoServerTypeConverter
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getHosts
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Execute shell commands on remote hosts over SSH.
 *
 * This command allows executing arbitrary shell commands on remote hosts with support for:
 * - Server type filtering (cassandra, stress, control)
 * - Host filtering via comma-separated host list
 * - Sequential or parallel execution
 * - Color-coded output (green for success, red for errors)
 * - Non-interleaved output display
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "exec",
    description = ["Execute a shell command on remote hosts"],
)
class Exec : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--type", "-t"],
        description = ["Server type (cassandra, stress, control)"],
        converter = [PicoServerTypeConverter::class],
    )
    var serverType: ServerType = ServerType.Cassandra

    @Parameters(
        description = ["Command to execute"],
        arity = "1..*",
    )
    lateinit var command: List<String>

    @Option(
        names = ["-p"],
        description = ["Execute in parallel"],
    )
    var parallel: Boolean = false

    override fun execute() {
        val commandString = command.joinToString(" ")

        if (commandString.isBlank()) {
            eventBus.emit(Event.Error("Command cannot be empty"))
            return
        }

        val hostList = clusterState.getHosts(serverType)
        if (hostList.isEmpty()) {
            eventBus.emit(Event.Message("No hosts found for server type: $serverType"))
            return
        }

        // Execute on hosts using HostOperationsService
        hostOperationsService.withHosts(clusterState.hosts, serverType, hosts.hostList, parallel = parallel) { host ->
            executeOnHost(host.toHost(), commandString)
        }
    }

    /**
     * Execute command on a single host and display output with color-coded header.
     *
     * @param host The host to execute on
     * @param commandString The command to execute
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeOnHost(
        host: Host,
        commandString: String,
    ) {
        try {
            val response = remoteOps.executeRemotely(host, commandString, output = true, secret = false)

            // Determine if there was an error (stderr present)
            val hasError = response.stderr.isNotEmpty()

            // Display output with color-coded header
            with(TermColors()) {
                val headerColor = if (hasError) red("=== ${host.alias} ===") else green("=== ${host.alias} ===")
                eventBus.emit(Event.Message(bold(headerColor)))

                // Display stdout if present
                if (response.text.isNotEmpty()) {
                    eventBus.emit(Event.Message(response.text))
                }

                // Display stderr if present
                if (hasError) {
                    eventBus.emit(Event.Message(red(response.stderr)))
                }
            }
        } catch (e: Exception) {
            // Handle execution failures
            with(TermColors()) {
                eventBus.emit(Event.Message(bold(red("=== ${host.alias} ==="))))
                eventBus.emit(Event.Message(red("Error executing command: ${e.message}")))
            }
        }
    }
}
