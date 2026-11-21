package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.aws.Aws
import com.rustyrazorblade.easydblab.commands.cassandra.Cassandra
import com.rustyrazorblade.easydblab.commands.exec.Exec
import com.rustyrazorblade.easydblab.commands.ingress.Ingress
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearch
import com.rustyrazorblade.easydblab.commands.profile.Profile
import com.rustyrazorblade.easydblab.commands.socks.Socks
import com.rustyrazorblade.easydblab.commands.spark.Spark
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.DefaultCommandExecutor
import com.rustyrazorblade.easydblab.services.ResourceManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jline.keymap.KeyMap
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.shell.Shell
import org.jline.shell.widget.CommandTailTipWidgets
import org.jline.terminal.TerminalBuilder
import org.koin.core.component.get
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command

/**
 * The root of the REPL's command tree: every easy-db-lab command the REPL offers.
 * This is used only in the REPL context; `cls`, `help` and `exit` are shell commands.
 */
@Command(
    name = "",
    description = ["easy-db-lab interactive shell"],
    subcommands = [
        // Top-level commands
        Version::class,
        Clean::class,
        Ip::class,
        Hosts::class,
        Status::class,
        Exec::class,
        ConfigureAxonOps::class,
        UploadAuthorizedKeys::class,
        ShowIamPolicies::class,
        ConfigureAWS::class,
        PruneAMIs::class,
        BuildBaseImage::class,
        BuildCassandraImage::class,
        BuildImage::class,
        Init::class,
        SetupInstance::class,
        Up::class,
        Socks::class,
        Ingress::class,
        // Parent command groups
        Spark::class,
        Cassandra::class,
        OpenSearch::class,
        Aws::class,
        Profile::class,
    ],
)
class ShellCommands : Runnable {
    override fun run() {
        // No-op - shell handles command execution
    }
}

/**
 * Interactive REPL with full PicoCLI integration, on jline's shell.
 *
 * Features:
 * - Tab completion for commands, subcommands and options, at any depth
 * - Command history with persistence
 * - Inline help via tail tips (toggle with Alt+S)
 * - Built-in shell commands (cls/clear, exit/quit)
 */
@RequireProfileSetup
@Command(
    name = "repl",
    description = ["Start interactive REPL with full tab completion"],
)
class Repl : PicoBaseCommand() {
    private val resourceManager: ResourceManager by inject()

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun execute() {
        // Mark context as interactive so CQL sessions stay open across commands
        context.isInteractive = true

        try {
            runCatchingException { runRepl() }.onFailure { e ->
                log.error(e) { "Error in REPL" }
                eventBus.emit(Event.Command.ReplStartError(e.message ?: e::class.simpleName ?: "Unknown error"))
            }
        } finally {
            // Clean up resources on REPL exit
            log.debug { "REPL exiting, cleaning up resources" }
            resourceManager.closeAll()
        }
    }

    private fun runRepl() {
        val terminal = TerminalBuilder.builder().name("easy-db-lab").build()
        val group = ReplCommandGroup(createCommandLine(), terminal)
        val shell =
            ReplLines
                .literal(Shell.builder())
                .terminal(terminal)
                .prompt(PROMPT)
                .groups(group)
                .helpCommands(true)
                .historyFile(context.profileDir.resolve(".repl_history").toPath())
                .variable(LineReader.LIST_MAX, MAX_COMPLETION_CANDIDATES)
                .onReaderReady { reader -> enableTailTips(reader, group) }
                .build()

        printWelcomeMessage()
        // The shell does not own a terminal it was given, so it is closed here.
        terminal.use { shell.use { it.run() } }
    }

    /** Command descriptions under the input line, toggled with Alt+S. */
    private fun enableTailTips(
        reader: LineReader,
        group: ReplCommandGroup,
    ) {
        val widgets =
            CommandTailTipWidgets(
                reader,
                { line -> group.describe(line.args()) },
                TAILTIP_DESCRIPTION_LINES,
                CommandTailTipWidgets.TipType.COMPLETER,
            )
        widgets.enable()
        reader.keyMaps[LineReader.MAIN]?.bind(Reference(TAILTIP_TOGGLE_WIDGET), KeyMap.alt('s'))
    }

    private fun createCommandLine(): CommandLine {
        val cmd = CommandLine(ShellCommands::class.java, KoinCommandFactory())

        // Set execution strategy to use our CommandExecutor
        // Get CommandExecutor lazily to avoid resolving AWS dependencies on REPL startup
        cmd.executionStrategy =
            CommandLine.IExecutionStrategy { parseResult ->
                var currentParseResult = parseResult.subcommand()
                while (currentParseResult?.subcommand() != null) {
                    currentParseResult = currentParseResult.subcommand()
                }

                if (currentParseResult != null) {
                    val userObj = currentParseResult.commandSpec().userObject()
                    if (userObj is PicoCommand) {
                        // Get CommandExecutor lazily when a command actually runs
                        val executor = get<CommandExecutor>() as DefaultCommandExecutor
                        return@IExecutionStrategy executor.executeTopLevel(userObj)
                    }
                }

                CommandLine.RunLast().execute(parseResult)
            }

        return cmd
    }

    private fun printWelcomeMessage() {
        println(
            "easy-db-lab interactive shell. Type 'help' for commands, TAB for completion.\n" +
                "Press Alt+S to toggle command description tips.\n",
        )
    }
}

private const val PROMPT = "easy-db-lab> "
private const val MAX_COMPLETION_CANDIDATES = 50
private const val TAILTIP_DESCRIPTION_LINES = 5

/** jline's widget that turns the tail tips on and off. */
private const val TAILTIP_TOGGLE_WIDGET = "tailtip-toggle"
