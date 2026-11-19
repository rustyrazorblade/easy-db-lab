package com.rustyrazorblade.easydblab.commands.exec

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.converters.PicoServerTypeConverter
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.EasyDBLabException
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.providers.ssm.SsmForwardNotReadyException
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs a command on remote hosts as a transient systemd unit, through `bash -c`.
 *
 * A single argument is a shell command line (`exec run "uname -n; uptime"`); several arguments
 * are quoted one by one, so each keeps its boundaries. The unit is named `edl-exec-<name>`, from
 * `--name` or from the command's first word, reduced to the characters systemd allows. A run
 * that fails on any host is reported per host and makes the command exit non-zero.
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "run",
    description = ["Execute a command on remote hosts via systemd-run"],
)
class ExecRun : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val nodeOutput: NodeOutput by inject()

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
        val commandString = command.singleOrNull() ?: command.joinToString(" ") { it.shellQuote() }

        if (commandString.isBlank()) {
            eventBus.emit(Event.Command.EmptyCommand)
            return
        }

        val hostList = clusterState.getHosts(serverType)
        if (hostList.isEmpty()) {
            eventBus.emit(Event.Command.NoHostsFound(serverType.toString()))
            return
        }

        val failedHosts = ConcurrentLinkedQueue<String>()
        hostOperationsService.withHosts(clusterState.hosts, serverType, hosts.hostList, parallel = parallel) { host ->
            if (!executeOnHost(host.toHost(), commandString)) failedHosts.add(host.alias)
        }
        if (failedHosts.isNotEmpty()) {
            throw CommandFailedException("exec run failed on: ${failedHosts.sorted().joinToString(", ")}")
        }
    }

    /**
     * Runs [commandString] on [host] and returns whether it succeeded; a failure is reported as an event.
     *
     * The failures that reach here are the ones the SSH layer reports: a transport [IOException], a
     * [RemoteCommandFailedException] or other [EasyDBLabException], and an [SsmForwardNotReadyException]
     * from the `ssm` transport, and an [IllegalArgumentException] from routing a host. Anything else is a bug and propagates.
     */
    private fun executeOnHost(
        host: Host,
        commandString: String,
    ): Boolean {
        val systemdUnit = execUnitName(deriveUnitName(commandString))

        val systemdRunCmd = buildSystemdRunCommand(systemdUnit, commandString)

        val failure =
            try {
                if (background) {
                    remoteOps.executeRemotely(host, systemdRunCmd, output = true, secret = false)
                    eventBus.emit(Event.Command.ToolStarted(host.alias, systemdUnit, commandString))
                } else {
                    runInForeground(host, systemdUnit, systemdRunCmd)
                }
                return true
            } catch (e: IOException) {
                e
            } catch (e: EasyDBLabException) {
                e
            } catch (e: SsmForwardNotReadyException) {
                e
            } catch (e: IllegalArgumentException) {
                // The ssm route refuses a host that has no instance ID.
                e
            }
        eventBus.emit(Event.Command.HostExecError(host.alias, reason(failure)))
        return false
    }

    /**
     * Why [failure] failed, for the host's error event. A failed remote command already printed its
     * stdout and stderr, tagged with the host, while it ran, so only its summary is repeated here.
     */
    private fun reason(failure: Exception): String =
        when (failure) {
            is RemoteCommandFailedException -> failure.summary
            else -> failure.message ?: failure::class.simpleName ?: "Unknown error"
        }

    /**
     * Runs the unit and waits for it, then prints its output from the journal. `systemd-run --wait`
     * exits non-zero when the unit fails, so the journal is printed on failure too: the unit's own
     * output is the reason it failed. The run's failure is rethrown, not the journal's.
     */
    private fun runInForeground(
        host: Host,
        systemdUnit: String,
        systemdRunCmd: String,
    ) {
        val run = runCatching { remoteOps.executeRemotely(host, systemdRunCmd, output = true, secret = false) }
        if (run.isFailure) runCatching { printUnitOutput(host, systemdUnit) } else printUnitOutput(host, systemdUnit)
        run.getOrThrow()
    }

    /**
     * Prints the unit's journal, each line tagged with the host, so parallel hosts never interleave
     * inside a line. An empty journal says so, so a host that printed nothing is not mistaken for one
     * that was skipped.
     */
    private fun printUnitOutput(
        host: Host,
        systemdUnit: String,
    ) {
        val journalCmd = "sudo journalctl --unit=$systemdUnit --no-pager --output=cat"
        val journal = remoteOps.executeRemotely(host, journalCmd, output = true, secret = false)
        if (journal.text.isBlank() && journal.stderr.isBlank()) nodeOutput.notice(host.alias, NO_OUTPUT_NOTICE)
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
        val epoch = System.currentTimeMillis() / Constants.Time.MILLIS_PER_SECOND
        return "$toolName-$epoch"
    }

    internal fun buildSystemdRunCommand(
        unitName: String,
        commandString: String,
    ): String {
        val waitFlag = if (background) "" else " --wait"
        return "sudo systemd-run --unit=$unitName" +
            waitFlag +
            " -- bash -c ${commandString.shellQuote()}"
    }

    private companion object {
        const val NO_OUTPUT_NOTICE = "no output"
    }
}
