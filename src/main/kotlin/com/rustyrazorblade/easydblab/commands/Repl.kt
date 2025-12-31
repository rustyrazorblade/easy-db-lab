package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.aws.Aws
import com.rustyrazorblade.easydblab.commands.cassandra.Cassandra
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouse
import com.rustyrazorblade.easydblab.commands.k8.K8
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearch
import com.rustyrazorblade.easydblab.commands.spark.Spark
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.DefaultCommandExecutor
import com.rustyrazorblade.easydblab.services.ResourceManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jline.console.SystemRegistry
import org.jline.console.impl.SystemRegistryImpl
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Parser
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.widget.TailTipWidgets
import org.koin.core.component.get
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.shell.jline3.PicocliCommands
import java.nio.file.Paths
import java.util.function.Supplier

/**
 * Shell-specific command that includes all easy-db-lab commands plus ClearScreen.
 * This is used only in the REPL context.
 */
@Command(
    name = "",
    description = ["easy-db-lab interactive shell"],
    subcommands = [
        // Shell-specific commands
        PicocliCommands.ClearScreen::class,
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
        SetupProfile::class,
        // Parent command groups
        Spark::class,
        K8::class,
        ClickHouse::class,
        Cassandra::class,
        OpenSearch::class,
        Aws::class,
    ],
)
class ShellCommands : Runnable {
    override fun run() {
        // No-op - shell handles command execution
    }
}

/**
 * Interactive REPL with full PicoCLI integration.
 *
 * Features:
 * - Tab completion for commands, options, and arguments
 * - Command history with persistence
 * - Inline help via TailTipWidgets (toggle with Alt+S)
 * - Built-in shell commands (clear, exit)
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
            val terminal = createTerminal()
            val commandLine = createCommandLine(terminal)
            val picocliCommands = PicocliCommands(commandLine)
            val parser: Parser = DefaultParser()

            // Set up working directory supplier
            val workDir = Supplier { Paths.get(context.workingDirectory.absolutePath) }

            // Create system registry with just picocli commands (skip builtins for simplicity)
            val systemRegistry: SystemRegistry = SystemRegistryImpl(parser, terminal, workDir, null)
            systemRegistry.setCommandRegistries(picocliCommands)

            // Build LineReader with completers
            val reader = createLineReader(terminal, systemRegistry, parser)

            // Set up TailTipWidgets for command descriptions
            val widgets =
                TailTipWidgets(
                    reader,
                    systemRegistry::commandDescription,
                    TAILTIP_DESCRIPTION_LINES,
                    TailTipWidgets.TipType.COMPLETER,
                )
            widgets.enable()

            printWelcomeMessage()

            // Run the REPL loop
            runReplLoop(reader, systemRegistry)
        } catch (e: Exception) {
            log.error(e) { "Error in REPL" }
            outputHandler.handleError("Error starting REPL: ${e.message ?: e::class.simpleName}", e)
        } finally {
            // Clean up resources on REPL exit
            log.debug { "REPL exiting, cleaning up resources" }
            resourceManager.closeAll()
        }
    }

    private fun createTerminal(): Terminal =
        TerminalBuilder
            .builder()
            .name("easy-db-lab")
            .build()

    private fun createCommandLine(terminal: Terminal): CommandLine {
        // Use PicocliCommandsFactory to support ClearScreen which needs Terminal access
        val picocliFactory = PicocliCommands.PicocliCommandsFactory()
        val koinFactory = KoinCommandFactory()

        // Chain factories: try picocli factory first (for ClearScreen), then Koin (for commands)
        val chainedFactory = ChainedCommandFactory(picocliFactory, koinFactory)

        val cmd = CommandLine(ShellCommands::class.java, chainedFactory)

        // Set terminal for ClearScreen command
        picocliFactory.setTerminal(terminal)

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

    private fun createLineReader(
        terminal: Terminal,
        systemRegistry: SystemRegistry,
        parser: Parser,
    ): LineReader =
        LineReaderBuilder
            .builder()
            .terminal(terminal)
            .completer(systemRegistry.completer())
            .parser(parser)
            .variable(LineReader.LIST_MAX, MAX_COMPLETION_CANDIDATES)
            .variable(LineReader.HISTORY_FILE, context.profileDir.resolve(".repl_history").absolutePath)
            .build()

    private fun printWelcomeMessage() {
        outputHandler.handleMessage("easy-db-lab interactive shell. Type 'help' for commands, TAB for completion.")
        outputHandler.handleMessage("Press Alt+S to toggle command description tips.")
        outputHandler.handleMessage("")
    }

    private fun runReplLoop(
        reader: LineReader,
        systemRegistry: SystemRegistry,
    ) {
        while (true) {
            try {
                systemRegistry.cleanUp()
                val line = reader.readLine(PROMPT)

                if (line.isBlank()) continue

                // Handle exit
                if (line.trim().equals("exit", ignoreCase = true) ||
                    line.trim().equals("quit", ignoreCase = true)
                ) {
                    break
                }

                // Execute through system registry (handles builtins and picocli)
                systemRegistry.execute(line)
            } catch (e: UserInterruptException) {
                // Ctrl+C - continue loop
            } catch (e: EndOfFileException) {
                // Ctrl+D - exit
                break
            } catch (e: Exception) {
                systemRegistry.trace(e)
            }
        }
    }
}

private const val PROMPT = "easy-db-lab> "
private const val MAX_COMPLETION_CANDIDATES = 50
private const val TAILTIP_DESCRIPTION_LINES = 5

/**
 * Factory that chains multiple PicoCLI factories together.
 * Tries each factory in order until one succeeds.
 */
private class ChainedCommandFactory(
    private vararg val factories: CommandLine.IFactory,
) : CommandLine.IFactory {
    override fun <K : Any> create(cls: Class<K>): K {
        for (factory in factories) {
            try {
                return factory.create(cls)
            } catch (e: Exception) {
                // Try next factory
            }
        }
        // Fall back to default factory
        return CommandLine.defaultFactory().create(cls)
    }
}
