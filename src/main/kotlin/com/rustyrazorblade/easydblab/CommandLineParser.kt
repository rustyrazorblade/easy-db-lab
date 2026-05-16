package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.commands.BuildBaseImage
import com.rustyrazorblade.easydblab.commands.BuildCassandraImage
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.Clean
import com.rustyrazorblade.easydblab.commands.Cleanup
import com.rustyrazorblade.easydblab.commands.Commands
import com.rustyrazorblade.easydblab.commands.ConfigureAWS
import com.rustyrazorblade.easydblab.commands.ConfigureAxonOps
import com.rustyrazorblade.easydblab.commands.Down
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
// import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouse
import com.rustyrazorblade.easydblab.commands.exec.Exec
import com.rustyrazorblade.easydblab.commands.grafana.Grafana
import com.rustyrazorblade.easydblab.commands.install.Install
import com.rustyrazorblade.easydblab.commands.install.WorkloadInstallCommandFactory
import com.rustyrazorblade.easydblab.commands.install.WorkloadRunnerCommandFactory
import com.rustyrazorblade.easydblab.commands.logs.Logs
import com.rustyrazorblade.easydblab.commands.metrics.Metrics
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearch
import com.rustyrazorblade.easydblab.commands.platform.Platform
import com.rustyrazorblade.easydblab.commands.spark.Spark
import com.rustyrazorblade.easydblab.commands.tailscale.Tailscale
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.DefaultCommandExecutor
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.TemplateVariables
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.File
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
        Commands::class,
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
        Grafana::class,
        // ClickHouse::class,
        Cassandra::class,
        OpenSearch::class,
        Aws::class,
        Logs::class,
        Metrics::class,
        Tailscale::class,
        Platform::class,
        Install::class,
        Cleanup::class,
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
    private val eventBus: EventBus by inject()

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
        if (isHelpRequested(input)) {
            val userConfigProvider: UserConfigProvider by inject()
            if (!userConfigProvider.isSetup()) {
                eventBus.emit(Event.Command.ProfileNotConfigured)
            }
        }

        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }

    init {
        registerDynamicInstallSubcommands()
        registerDynamicWorkloadSubcommands()
    }

    /**
     * Determines if the input represents a help request (no command or explicit help flag).
     */
    private fun isHelpRequested(input: Array<String>): Boolean = input.isEmpty() || input.singleOrNull() in listOf("--help", "-h")

    /**
     * Scans all available install templates (classpath + profile dir) for install.yaml files
     * and registers a dynamic PicoCLI subcommand under `install` for each one found.
     *
     * Cluster state is loaded to resolve `${VAR}` defaults; if state is unavailable the
     * raw `${VAR}` forms are kept as default text.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun registerDynamicInstallSubcommands() {
        val resolver = get<InstallTemplateResolver>()
        val templateVars =
            try {
                val state = get<ClusterStateManager>().load()
                TemplateVariables.from(state = state, workloadName = "", storageSize = "").toMap()
            } catch (e: Exception) {
                log.debug(e) { "Cluster state unavailable during install subcommand registration; using raw defaults" }
                emptyMap()
            }

        val factory = WorkloadInstallCommandFactory(templateVars)
        val installCL = commandLine.getSubcommands()["install"] ?: return

        for (name in resolver.listAvailableTemplates()) {
            val source =
                try {
                    resolver.resolve(name)
                } catch (e: Exception) {
                    continue
                }
            val config = resolver.loadInstallConfig(source) ?: continue
            if (name !in installCL.subcommands.keys) {
                installCL.addSubcommand(name, factory.build(config, source))
            }
        }
    }

    /**
     * Scans the working directory for installed workload directories and registers a top-level
     * PicoCLI subcommand for each one. A directory qualifies if it contains either:
     * - a `bin/` subdirectory with at least one executable script, or
     * - an `install.yaml` with at least one typed lifecycle phase.
     *
     * For example, `<cwd>/clickhouse/bin/start.sh` → `easy-db-lab clickhouse start`, or
     * `<cwd>/clickhouse/install.yaml` with a `start` phase → `easy-db-lab clickhouse start`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun registerDynamicWorkloadSubcommands() {
        val ctx = get<Context>()
        val factory = WorkloadRunnerCommandFactory()

        ctx.workingDirectory
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .forEach { workloadDir ->
                val hasBinScripts =
                    File(workloadDir, "bin").isDirectory &&
                        File(workloadDir, "bin")
                            .listFiles()
                            .orEmpty()
                            .any { it.isFile && (it.canExecute() || it.name.endsWith(".sh")) }
                val hasInstallYaml = File(workloadDir, "install.yaml").isFile

                if (!hasBinScripts && !hasInstallYaml) return@forEach

                val workloadName = workloadDir.name
                if (workloadName in commandLine.subcommands.keys) return@forEach

                try {
                    commandLine.addSubcommand(workloadName, factory.buildWorkloadGroup(workloadName, workloadDir))
                } catch (e: Exception) {
                    log.debug(e) { "Failed to register workload subcommand for $workloadName" }
                }
            }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
