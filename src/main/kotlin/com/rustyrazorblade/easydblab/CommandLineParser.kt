package com.rustyrazorblade.easydblab

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.commands.BuildBaseImage
import com.rustyrazorblade.easydblab.commands.BuildCassandraImage
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.Clean
import com.rustyrazorblade.easydblab.commands.ConfigureAWS
import com.rustyrazorblade.easydblab.commands.ConfigureAxonOps
import com.rustyrazorblade.easydblab.commands.Down
import com.rustyrazorblade.easydblab.commands.Exec
import com.rustyrazorblade.easydblab.commands.Hosts
import com.rustyrazorblade.easydblab.commands.Init
import com.rustyrazorblade.easydblab.commands.Ip
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.commands.PruneAMIs
import com.rustyrazorblade.easydblab.commands.Repl
import com.rustyrazorblade.easydblab.commands.Server
import com.rustyrazorblade.easydblab.commands.SetupInstance
import com.rustyrazorblade.easydblab.commands.SetupProfile
import com.rustyrazorblade.easydblab.commands.ShowIamPolicies
import com.rustyrazorblade.easydblab.commands.Status
import com.rustyrazorblade.easydblab.commands.Up
import com.rustyrazorblade.easydblab.commands.UploadAuthorizedKeys
import com.rustyrazorblade.easydblab.commands.Version
import com.rustyrazorblade.easydblab.commands.aws.Aws
import com.rustyrazorblade.easydblab.commands.cassandra.Cassandra
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouse
import com.rustyrazorblade.easydblab.commands.k8.K8
import com.rustyrazorblade.easydblab.commands.logs.Logs
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearch
import com.rustyrazorblade.easydblab.commands.spark.Spark
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.DefaultCommandExecutor
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import kotlin.system.exitProcess

/**
 * Root command for easy-db-lab CLI.
 * Declaratively registers all top-level commands and parent command groups.
 */
@Command(
    name = "easy-db-lab",
    description = ["Tool to create Cassandra lab environments in AWS"],
    mixinStandardHelpOptions = true,
    subcommands = [
        // Top-level commands
        Version::class,
        Clean::class,
        Down::class,
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
        Repl::class,
        Server::class,
        // Parent command groups
        Spark::class,
        K8::class,
        ClickHouse::class,
        Cassandra::class,
        OpenSearch::class,
        Aws::class,
        Logs::class,
    ],
)
class EasyDBLabCommand : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        // Show help when no subcommand is provided
        spec.commandLine().usage(System.out)
    }
}

/**
 * Command line parser using PicoCLI with Koin dependency injection.
 *
 * All commands are registered declaratively via @Command annotations.
 * KoinCommandFactory provides command instances with injected dependencies.
 */
class CommandLineParser : KoinComponent {
    private val outputHandler: OutputHandler by inject()

    /** The main PicoCLI CommandLine instance with all subcommands registered. */
    private val commandLine: CommandLine =
        CommandLine(EasyDBLabCommand::class.java, KoinCommandFactory()).apply {
            // Set exception handler to ensure non-zero exit code on exceptions
            executionExceptionHandler =
                CommandLine.IExecutionExceptionHandler { ex, cmd, _ ->
                    cmd.err.println(ex.message)
                    ex.printStackTrace(cmd.err)
                    Constants.ExitCodes.ERROR
                }

            // Set execution strategy to delegate to CommandExecutor for full lifecycle
            executionStrategy =
                CommandLine.IExecutionStrategy { parseResult ->
                    // Find the deepest subcommand (handles nested commands like "spark submit")
                    var currentParseResult = parseResult.subcommand()
                    while (currentParseResult?.subcommand() != null) {
                        currentParseResult = currentParseResult.subcommand()
                    }

                    // Execute PicoCommands through CommandExecutor for full lifecycle
                    // (requirements, execution, scheduled commands, backup)
                    if (currentParseResult != null) {
                        val cmd = currentParseResult.commandSpec().userObject()
                        if (cmd is PicoCommand) {
                            // Route ALL commands to CommandExecutor
                            // Profile check is handled by @RequireProfileSetup annotation in CommandExecutor
                            val executor = get<CommandExecutor>() as DefaultCommandExecutor
                            return@IExecutionStrategy executor.executeTopLevel(cmd)
                        }
                    }

                    // Fallback for non-PicoCommand (like root command help)
                    CommandLine.RunLast().execute(parseResult)
                }
        }

    @Suppress("SpreadOperator")
    fun eval(input: Array<String>) {
        // Use PicoCLI for all command execution
        val exitCode = commandLine.execute(*input)

        // Show profile setup hint if no command was provided and profile not configured
        if (input.isEmpty() || (input.size == 1 && (input[0] == "--help" || input[0] == "-h"))) {
            val userConfigProvider: UserConfigProvider by inject()
            if (!userConfigProvider.isSetup()) {
                with(TermColors()) {
                    outputHandler.handleMessage(
                        yellow(
                            """

                            Profile not configured. Please run 'easy-db-lab setup-profile' to configure your environment.

                            """.trimIndent(),
                        ),
                    )
                }
            }
        }

        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }
}
