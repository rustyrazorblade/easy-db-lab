package com.rustyrazorblade.easydblab

import com.github.ajalt.mordant.TermColors
import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.BuildBaseImage
import com.rustyrazorblade.easydblab.commands.BuildCassandraImage
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.Clean
import com.rustyrazorblade.easydblab.commands.ConfigureAWS
import com.rustyrazorblade.easydblab.commands.ConfigureAxonOps
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
import com.rustyrazorblade.easydblab.commands.UploadAuthorizedKeys
import com.rustyrazorblade.easydblab.commands.Version
import com.rustyrazorblade.easydblab.commands.aws.Aws
import com.rustyrazorblade.easydblab.commands.aws.Vpcs
import com.rustyrazorblade.easydblab.commands.cassandra.Cassandra
import com.rustyrazorblade.easydblab.commands.cassandra.Down
import com.rustyrazorblade.easydblab.commands.cassandra.DownloadConfig
import com.rustyrazorblade.easydblab.commands.cassandra.ListVersions
import com.rustyrazorblade.easydblab.commands.cassandra.Restart
import com.rustyrazorblade.easydblab.commands.cassandra.Start
import com.rustyrazorblade.easydblab.commands.cassandra.Stop
import com.rustyrazorblade.easydblab.commands.cassandra.Up
import com.rustyrazorblade.easydblab.commands.cassandra.UpdateConfig
import com.rustyrazorblade.easydblab.commands.cassandra.UseCassandra
import com.rustyrazorblade.easydblab.commands.cassandra.WriteConfig
import com.rustyrazorblade.easydblab.commands.cassandra.stress.Stress
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressFields
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressInfo
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressList
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressLogs
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStart
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStatus
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStop
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouse
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStart
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStatus
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStop
import com.rustyrazorblade.easydblab.commands.k8.K8
import com.rustyrazorblade.easydblab.commands.k8.K8Apply
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearch
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStart
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStatus
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStop
import com.rustyrazorblade.easydblab.commands.spark.Spark
import com.rustyrazorblade.easydblab.commands.spark.SparkJobs
import com.rustyrazorblade.easydblab.commands.spark.SparkLogs
import com.rustyrazorblade.easydblab.commands.spark.SparkStatus
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.services.ClusterBackupService
import com.rustyrazorblade.easydblab.services.StateReconstructionService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.io.File
import kotlin.system.exitProcess

/**
 * Represents a PicoCLI command registration.
 * Uses a factory function to create new instances for each execution.
 */
data class PicoCommandEntry(
    val name: String,
    val factory: () -> PicoCommand,
    val aliases: List<String> = listOf(),
)

/**
 * Root command for easy-db-lab CLI.
 * When no subcommand is provided, displays help.
 */
@Command(
    name = "easy-db-lab",
    description = ["Tool to create Cassandra lab environments in AWS"],
    mixinStandardHelpOptions = true,
)
class EasyDBLabCommand : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--vpc-id"],
        description = ["Reconstruct state from existing VPC. Requires ClusterId tag on VPC."],
    )
    var vpcId: String? = null

    @Option(
        names = ["--force"],
        description = ["Force state reconstruction even if state.json already exists"],
    )
    var force: Boolean = false

    override fun run() {
        // Show help when no subcommand is provided
        spec.commandLine().usage(System.out)
    }
}

class CommandLineParser(
    val context: Context,
) : KoinComponent {
    private val userConfig: User by inject()

    /** Registry for PicoCLI commands. */
    val picoCommands: List<PicoCommandEntry>

    /** Map of command names and aliases to their PicoCLI entries for fast lookup. */
    private val picoCommandMap: Map<String, PicoCommandEntry>

    /** The main PicoCLI CommandLine instance with all subcommands registered. */
    private val commandLine: CommandLine

    private val logger = KotlinLogging.logger {}
    private val regex = """("([^"\\]|\\.)*"|'([^'\\]|\\.)*'|[^\s"']+)+""".toRegex()
    private val dockerClientProvider: DockerClientProvider by inject()
    private val outputHandler: OutputHandler by inject()

    init {
        // PicoCLI commands - all commands are now fully migrated
        // Each entry uses a factory to create fresh instances for each execution
        picoCommands =
            listOf(
                PicoCommandEntry("version", { Version(context) }),
                PicoCommandEntry("clean", { Clean(context) }),
                PicoCommandEntry("list", { ListVersions(context) }, listOf("ls")),
                PicoCommandEntry("ip", { Ip(context) }),
                PicoCommandEntry("hosts", { Hosts(context) }),
                PicoCommandEntry("stop", { Stop(context) }),
                PicoCommandEntry("start", { Start(context) }),
                PicoCommandEntry("status", { Status(context) }),
                PicoCommandEntry("restart", { Restart(context) }),
                PicoCommandEntry("exec", { Exec(context) }),
                PicoCommandEntry("down", { Down(context) }),
                PicoCommandEntry("download-config", { DownloadConfig(context) }, listOf("dc")),
                PicoCommandEntry("write-config", { WriteConfig(context) }, listOf("wc")),
                PicoCommandEntry("update-config", { UpdateConfig(context) }, listOf("uc")),
                PicoCommandEntry("use", { UseCassandra(context) }),
                PicoCommandEntry("configure-axonops", { ConfigureAxonOps(context) }),
                PicoCommandEntry("upload-keys", { UploadAuthorizedKeys(context) }),
                PicoCommandEntry("show-iam-policies", { ShowIamPolicies(context) }, listOf("sip")),
                PicoCommandEntry("configure-aws", { ConfigureAWS(context) }),
                PicoCommandEntry("prune-amis", { PruneAMIs(context) }),
                PicoCommandEntry("build-base", { BuildBaseImage(context) }),
                PicoCommandEntry("build-cassandra", { BuildCassandraImage(context) }),
                PicoCommandEntry("build-image", { BuildImage(context) }),
                PicoCommandEntry("init", { Init(context) }),
                PicoCommandEntry("setup-instances", { SetupInstance(context) }, listOf("si")),
                PicoCommandEntry("up", { Up(context) }),
                PicoCommandEntry("setup-profile", { SetupProfile(context) }, listOf("setup")),
                PicoCommandEntry("repl", { Repl(context) }),
                PicoCommandEntry("server", { Server(context) }),
            )

        // Build lookup map including aliases
        picoCommandMap =
            buildMap {
                for (entry in picoCommands) {
                    put(entry.name, entry)
                    for (alias in entry.aliases) {
                        put(alias, entry)
                    }
                }
            }

        // Create root command and register all subcommands
        val rootCommand = EasyDBLabCommand()
        commandLine = CommandLine(rootCommand)

        // Register all subcommands with PicoCLI
        // Note: aliases should be defined in the @Command annotation on each command class
        for (entry in picoCommands) {
            val cmd = entry.factory()
            val subCommandLine = commandLine.addSubcommand(entry.name, cmd)
            // Register aliases from our PicoCommandEntry (for backward compatibility with REPL)
            for (alias in entry.aliases) {
                subCommandLine.commandSpec.aliases(*arrayOf(alias))
            }
        }

        // Register Spark parent command with its sub-commands
        // Spark is a parent command that contains submit, status, and jobs sub-commands
        // The sub-commands are PicoCommands that need Context, so we create them here
        // Note: We must create the CommandLine first and add subcommands before registering
        // with the parent, because addSubcommand() returns the parent, not the child
        val sparkCommandLine = CommandLine(Spark())
        sparkCommandLine.addSubcommand("submit", SparkSubmit(context))
        sparkCommandLine.addSubcommand("status", SparkStatus(context))
        sparkCommandLine.addSubcommand("jobs", SparkJobs(context))
        sparkCommandLine.addSubcommand("logs", SparkLogs(context))
        commandLine.addSubcommand("spark", sparkCommandLine)

        // Register K8s parent command with its sub-commands
        // K8 is a parent command for Kubernetes cluster operations
        val k8CommandLine = CommandLine(K8())
        k8CommandLine.addSubcommand("apply", K8Apply(context))
        commandLine.addSubcommand("k8", k8CommandLine)

        // Register ClickHouse parent command with its sub-commands
        // ClickHouse is a parent command for ClickHouse cluster operations on K8s
        val clickHouseCommandLine = CommandLine(ClickHouse())
        clickHouseCommandLine.addSubcommand("start", ClickHouseStart(context))
        clickHouseCommandLine.addSubcommand("status", ClickHouseStatus(context))
        clickHouseCommandLine.addSubcommand("stop", ClickHouseStop(context))
        commandLine.addSubcommand("clickhouse", clickHouseCommandLine)

        // Register Cassandra parent command with nested stress commands and cluster operations
        // Cassandra is a parent command for Cassandra tooling operations
        val cassandraCommandLine = CommandLine(Cassandra())

        // Stress is a nested parent command for cassandra-easy-stress job operations on K8s
        val stressCommandLine = CommandLine(Stress())
        stressCommandLine.addSubcommand("start", StressStart(context))
        stressCommandLine.addSubcommand("status", StressStatus(context))
        stressCommandLine.addSubcommand("stop", StressStop(context))
        stressCommandLine.addSubcommand("logs", StressLogs(context))
        stressCommandLine.addSubcommand("list", StressList(context))
        stressCommandLine.addSubcommand("fields", StressFields(context))
        stressCommandLine.addSubcommand("info", StressInfo(context))
        cassandraCommandLine.addSubcommand("stress", stressCommandLine)

        // Cassandra cluster management commands (also available at top level for backwards compatibility)
        cassandraCommandLine.addSubcommand("up", Up(context))
        cassandraCommandLine.addSubcommand("down", Down(context))
        cassandraCommandLine.addSubcommand("start", Start(context))
        cassandraCommandLine.addSubcommand("stop", Stop(context))
        cassandraCommandLine.addSubcommand("restart", Restart(context))
        cassandraCommandLine.addSubcommand("list", ListVersions(context))
        cassandraCommandLine.addSubcommand("use", UseCassandra(context))
        cassandraCommandLine.addSubcommand("download-config", DownloadConfig(context))
        cassandraCommandLine.addSubcommand("write-config", WriteConfig(context))
        cassandraCommandLine.addSubcommand("update-config", UpdateConfig(context))

        commandLine.addSubcommand("cassandra", cassandraCommandLine)

        // Register OpenSearch parent command with its sub-commands
        // OpenSearch is a parent command for AWS-managed OpenSearch domain operations
        val openSearchCommandLine = CommandLine(OpenSearch())
        openSearchCommandLine.addSubcommand("start", OpenSearchStart(context))
        openSearchCommandLine.addSubcommand("status", OpenSearchStatus(context))
        openSearchCommandLine.addSubcommand("stop", OpenSearchStop(context))
        commandLine.addSubcommand("opensearch", openSearchCommandLine)

        // Register AWS parent command with its sub-commands
        // AWS is a parent command for AWS resource discovery and management
        val awsCommandLine = CommandLine(Aws())
        awsCommandLine.addSubcommand("vpcs", Vpcs(context))
        commandLine.addSubcommand("aws", awsCommandLine)

        // Set exception handler to ensure non-zero exit code on exceptions
        commandLine.executionExceptionHandler =
            CommandLine.IExecutionExceptionHandler { ex, cmd, _ ->
                cmd.err.println(ex.message)
                ex.printStackTrace(cmd.err)
                Constants.ExitCodes.ERROR
            }

        // Set execution strategy to check requirements before running commands
        commandLine.executionStrategy =
            CommandLine.IExecutionStrategy { parseResult ->
                // Get the root command to check for --vpc-id option
                val rootCmd = parseResult.commandSpec().userObject()
                if (rootCmd is EasyDBLabCommand && rootCmd.vpcId != null) {
                    // Reconstruct state from VPC before running any subcommand
                    handleVpcIdStateReconstruction(rootCmd.vpcId!!, rootCmd.force)
                }

                // Find the deepest subcommand (handles nested commands like "spark submit")
                var currentParseResult = parseResult.subcommand()
                while (currentParseResult?.subcommand() != null) {
                    currentParseResult = currentParseResult.subcommand()
                }

                // Check requirements for the command if present
                if (currentParseResult != null) {
                    val cmd = currentParseResult.commandSpec().userObject()
                    if (cmd is PicoCommand) {
                        checkCommandRequirements(cmd)
                    }
                }
                // Execute the command using default RunLast strategy
                val exitCode = CommandLine.RunLast().execute(parseResult)

                // After successful execution, check for @TriggerBackup annotation
                if (exitCode == 0 && currentParseResult != null) {
                    val cmd = currentParseResult.commandSpec().userObject()
                    if (cmd::class.annotations.any { it is TriggerBackup }) {
                        performIncrementalBackup()
                    }
                }

                exitCode
            }
    }

    /**
     * Handles state reconstruction from a VPC ID.
     *
     * This is triggered when --vpc-id is provided on the command line.
     * It reconstructs the local state.json from AWS resources associated with the VPC,
     * and restores cluster configuration files (kubeconfig, k8s manifests, cassandra.patch.yaml) from S3.
     *
     * @param vpcId The VPC ID to reconstruct state from
     * @param force If true, overwrites existing state.json
     * @throws IllegalStateException if state.json exists and --force is not provided
     */
    private fun handleVpcIdStateReconstruction(
        vpcId: String,
        force: Boolean,
    ) {
        val clusterStateManager: ClusterStateManager by inject()
        val stateReconstructionService: StateReconstructionService by inject()
        val clusterBackupService: ClusterBackupService by inject()

        // Check if state.json already exists
        if (clusterStateManager.exists() && !force) {
            error(
                "state.json already exists. Use --force to overwrite, or remove the file manually.\n" +
                    "Warning: Overwriting will lose any local configuration.",
            )
        }

        outputHandler.handleMessage("Reconstructing state from VPC: $vpcId")
        val reconstructedState = stateReconstructionService.reconstructFromVpc(vpcId)
        clusterStateManager.save(reconstructedState)
        outputHandler.handleMessage("State reconstructed successfully:")
        outputHandler.handleMessage("  Cluster name: ${reconstructedState.name}")
        outputHandler.handleMessage("  Cluster ID: ${reconstructedState.clusterId}")
        outputHandler.handleMessage("  Hosts: ${reconstructedState.hosts.values.sumOf { it.size }}")
        outputHandler.handleMessage("  S3 bucket: ${reconstructedState.s3Bucket ?: "not found"}")

        // Restore cluster configuration files from S3 if S3 bucket is configured
        if (reconstructedState.s3Bucket != null) {
            outputHandler.handleMessage("Restoring cluster configuration from S3...")
            val workingDirectory = context.workingDirectory.absolutePath
            val restoreResult = clusterBackupService.restoreAll(workingDirectory, reconstructedState)

            restoreResult
                .onSuccess { result ->
                    if (result.hasRestores()) {
                        outputHandler.handleMessage("Configuration restored from S3:")
                        for (target in result.successfulTargets) {
                            outputHandler.handleMessage("  - ${target.displayName}")
                        }
                    } else {
                        outputHandler.handleMessage("No configuration files found in S3 to restore")
                    }
                }.onFailure { error ->
                    logger.warn(error) { "Failed to restore configuration from S3" }
                    outputHandler.handleMessage("Warning: Failed to restore configuration from S3: ${error.message}")
                }
        }
    }

    /**
     * Performs incremental backup of configuration files to S3.
     *
     * This is called after commands annotated with @TriggerBackup complete successfully.
     * It computes hashes of all backup targets and uploads only files that have changed
     * since the last backup.
     */
    private fun performIncrementalBackup() {
        val clusterStateManager: ClusterStateManager by inject()
        val clusterBackupService: ClusterBackupService by inject()

        // Skip if no state file exists
        if (!clusterStateManager.exists()) {
            return
        }

        val state = clusterStateManager.load()

        // Skip if no S3 bucket configured
        if (state.s3Bucket.isNullOrBlank()) {
            return
        }

        clusterBackupService
            .backupChanged(context.workingDirectory.absolutePath, state)
            .onSuccess { result ->
                if (result.filesUploaded > 0) {
                    // Update state with new hashes
                    state.backupHashes = state.backupHashes + result.updatedHashes
                    clusterStateManager.save(state)
                    outputHandler.handleMessage("Backed up ${result.filesUploaded} changed configuration files to S3")
                }
            }.onFailure { e ->
                logger.warn(e) { "Incremental backup failed" }
                outputHandler.handleMessage("Warning: Incremental backup failed: ${e.message}")
            }
    }

    /**
     * Checks if a command name or alias refers to a registered command.
     */
    fun isPicoCommand(name: String): Boolean = picoCommandMap.containsKey(name)

    // For the repl
    fun eval(input: String) {
        val matches = regex.findAll(input)
        val result = mutableListOf<String>()

        for (match in matches) {
            result.add(match.value.trim('"', '\''))
        }

        return eval(result.toTypedArray())
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

    /**
     * Checks and enforces command requirements based on annotations.
     */
    private fun checkCommandRequirements(command: Any) {
        // Check if the command requires profile setup
        if (command::class.annotations.any { it is RequireProfileSetup }) {
            val userConfigProvider: UserConfigProvider by inject()
            if (!userConfigProvider.isSetup()) {
                // Run setup command
                val setupCommand = SetupProfile(context)
                setupCommand.execute()

                // Show message and exit
                with(TermColors()) {
                    outputHandler.handleMessage(green("\nYou can now run the command again."))
                }
                exitProcess(0)
            }
        }
        // Check if the command requires Docker
        if (command::class.annotations.any { it is RequireDocker }) {
            if (!checkDockerAvailability()) {
                outputHandler.handleError("Error: Docker is not available or not running.")
                outputHandler.handleError(
                    "Please ensure Docker is installed and running before executing this command.",
                )
                exitProcess(1)
            }
        }
        // Check if the command requires an SSH key
        if (command::class.annotations.any { it is RequireSSHKey }) {
            if (!checkSSHKeyAvailability()) {
                outputHandler.handleError("SSH key not found at ${userConfig.sshKeyPath}")
                exitProcess(1)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkDockerAvailability(): Boolean =
        try {
            val dockerClient = dockerClientProvider.getDockerClient()
            // Try to list images as a simple health check
            dockerClient.listImages("", "")
            true
        } catch (e: Exception) {
            logger.error(e) { "Docker availability check failed" }
            false
        }

    private fun checkSSHKeyAvailability(): Boolean = File(userConfig.sshKeyPath).exists()
}
