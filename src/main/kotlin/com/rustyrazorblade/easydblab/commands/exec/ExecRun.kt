package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
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

@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "run",
    description = ["Execute a command on remote hosts via systemd-run"],
)
class ExecRun : PicoBaseCommand() {
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
        names = ["--bg"],
        description = ["Run in background (returns immediately)"],
    )
    var background: Boolean = false

    @Option(
        names = ["--name"],
        description = ["Name for the systemd unit (auto-derived if not provided)"],
    )
    var unitNameOverride: String? = null

    @Option(
        names = ["-p"],
        description = ["Execute in parallel"],
    )
    var parallel: Boolean = false

    override fun execute() {
        val commandString = command.joinToString(" ")

        if (commandString.isBlank()) {
            eventBus.emit(Event.Command.EmptyCommand)
            return
        }

        val hostList = clusterState.getHosts(serverType)
        if (hostList.isEmpty()) {
            eventBus.emit(Event.Command.NoHostsFound(serverType.toString()))
            return
        }

        hostOperationsService.withHosts(clusterState.hosts, serverType, hosts.hostList, parallel = parallel) { host ->
            executeOnHost(host.toHost(), commandString)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeOnHost(
        host: Host,
        commandString: String,
    ) {
        val unitName = deriveUnitName(commandString)
        val logFile = "/var/log/easydblab/tools/$unitName.log"
        val systemdUnit = "edl-exec-$unitName"

        val systemdRunCmd = buildSystemdRunCommand(systemdUnit, logFile, commandString)

        try {
            if (background) {
                remoteOps.executeRemotely(host, systemdRunCmd, output = true, secret = false)
                eventBus.emit(Event.Command.ToolStarted(host.alias, systemdUnit, commandString))
            } else {
                remoteOps.executeRemotely(host, systemdRunCmd, output = true, secret = false)
                // Read and display the log file after foreground execution
                val logOutput = remoteOps.executeRemotely(host, "cat $logFile 2>/dev/null", output = false, secret = false)
                eventBus.emit(Event.Command.HostExecHeader(host.alias))
                if (logOutput.text.isNotEmpty()) {
                    eventBus.emit(Event.Command.HostExecOutput(logOutput.text))
                }
                if (logOutput.stderr.isNotEmpty()) {
                    eventBus.emit(Event.Command.HostExecStderr(logOutput.stderr))
                }
            }
        } catch (e: Exception) {
            eventBus.emit(Event.Command.HostExecError(host.alias, e.message ?: e::class.simpleName ?: "Unknown error"))
        }
    }

    internal fun deriveUnitName(commandString: String): String {
        unitNameOverride?.let { return it }
        val toolName =
            commandString
                .trim()
                .split("\\s+".toRegex())
                .first()
                .split("/")
                .last()
        val epoch = System.currentTimeMillis() / 1000
        return "$toolName-$epoch"
    }

    internal fun buildSystemdRunCommand(
        unitName: String,
        logFile: String,
        commandString: String,
    ): String {
        val waitFlag = if (background) "" else " --wait"
        return "sudo systemd-run --unit=$unitName" +
            " --property=StandardOutput=file:$logFile" +
            " --property=StandardError=file:$logFile" +
            waitFlag +
            " -- $commandString"
    }
}
