package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.commands.SetupProfile
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.proxy.ProxyAvailability
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.ArrayDeque
import kotlin.system.exitProcess

/**
 * Service for executing commands with full lifecycle support.
 *
 * Provides two scheduling modes:
 * - [execute]: Execute a command immediately with full lifecycle. Use for mid-command delegation.
 * - [schedule]: Schedule a command to run after the current command's lifecycle completes.
 *
 * The full lifecycle includes:
 * 1. Requirement checks (@RequireProfileSetup, @RequireSSHKey, @RequireDocker, @RequiresProxy)
 * 2. Pre-execute hooks (@PreExecute)
 * 3. Command execution
 * 4. Post-execute hooks (@PostExecute)
 * 5. Post-success actions (@TriggerBackup)
 */
interface CommandExecutor {
    /**
     * Execute a command immediately with full lifecycle.
     * Blocks until command completes. Use for mid-command delegation.
     *
     * Example usage:
     * ```
     * commandExecutor.execute { ConfigureAxonOps(context) }
     * ```
     *
     * @param commandFactory Lambda that creates the command instance
     * @return Exit code (0 for success, non-zero for failure)
     */
    fun <T : PicoCommand> execute(commandFactory: () -> T): Int

    /**
     * Schedule a command to run after the current command's lifecycle completes.
     * The scheduled command runs with its own full lifecycle.
     *
     * Example usage:
     * ```
     * commandExecutor.schedule { Up(context) }
     * ```
     *
     * @param commandFactory Lambda that creates the command instance
     */
    fun <T : PicoCommand> schedule(commandFactory: () -> T)
}

/**
 * Dependencies for requirement checking during command execution.
 *
 * @property userConfigProvider Provides user configuration state
 * @property dockerClientProvider Provides Docker client for availability checks
 */
data class RequirementCheckDeps(
    val userConfigProvider: UserConfigProvider,
    val dockerClientProvider: DockerClientProvider,
)

/**
 * Default implementation of CommandExecutor.
 *
 * Uses a thread-local queue to support nested command chains - each thread
 * maintains its own queue of scheduled commands.
 */
class DefaultCommandExecutor(
    private val context: Context,
    private val clusterStateManager: ClusterStateManager,
    private val requirementCheckDeps: RequirementCheckDeps,
    private val resourceManager: ResourceManager,
    private val eventBus: EventBus,
    private val socksProxyService: SocksProxyService,
    private val proxyAvailability: ProxyAvailability,
) : CommandExecutor,
    KoinComponent {
    // Lazy injection - only resolves when first accessed (defers AWS dependency chain)
    private val backupRestoreService: BackupRestoreService by inject()

    // Thread-local queue for deferred command factories (supports nested command chains)
    private val scheduledQueue = ThreadLocal.withInitial { ArrayDeque<() -> PicoCommand>() }

    override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
        val command = commandFactory()
        return executeWithLifecycle(command)
    }

    override fun <T : PicoCommand> schedule(commandFactory: () -> T) {
        scheduledQueue.get().addLast(commandFactory)
    }

    /**
     * Execute a command with full lifecycle, then process any scheduled commands.
     * This is the entry point for top-level command execution (called by CommandLineParser).
     *
     * The SOCKS5 proxy is started by [checkRequirements] — see [ensureProxyRunning] — only for
     * the command(s) actually executed (this one, and any it schedules or delegates to) that
     * carry [RequiresProxy]. A command that never declares the dependency never starts a tunnel.
     *
     * @param command The command to execute
     * @return Exit code (0 for success, non-zero for failure)
     */
    fun executeTopLevel(command: PicoCommand): Int {
        // VPC reconstruction from environment variable (if set)
        handleVpcReconstructionFromEnv()

        try {
            val exitCode = executeWithLifecycle(command)

            // Process scheduled commands (each with full lifecycle)
            // Stop on first failure
            while (scheduledQueue.get().isNotEmpty()) {
                val commandFactory = scheduledQueue.get().removeFirst()
                val scheduledCommand = commandFactory()
                val scheduledExitCode = executeWithLifecycle(scheduledCommand)
                if (scheduledExitCode != 0) {
                    // Clear remaining scheduled commands on failure
                    scheduledQueue.get().clear()
                    return scheduledExitCode
                }
            }

            return exitCode
        } finally {
            // Clean up resources in non-interactive mode (CLI commands)
            // In interactive mode (REPL/Server), resources stay open for reuse
            if (!context.isInteractive) {
                log.debug { "Non-interactive mode: cleaning up resources" }
                resourceManager.closeAll()
            }
        }
    }

    /**
     * Starts the SOCKS5 proxy for commands that carry [RequiresProxy] (see [checkRequirements]),
     * when the cluster is provisioned, infrastructure is UP, and Tailscale is not active.
     *
     * A failure to establish the proxy propagates to the caller rather than being swallowed:
     * the annotation is the assertion that the command cannot proceed without a working tunnel,
     * so publishing a dead one (or silently running without it) would only defer the failure to
     * a more confusing Fabric8/HTTP error later. `Down` is never annotated, so it never starts a
     * tunnel it would immediately have to tear down.
     */
    private fun ensureProxyRunning() {
        if (!clusterStateManager.exists()) return
        val state =
            try {
                clusterStateManager.load()
            } catch (e: Exception) {
                log.debug(e) { "Could not load cluster state for proxy startup check" }
                return
            }
        if (!state.isInfrastructureUp()) return
        if (state.isTailscaleEnabled()) return
        val controlHost = state.getControlHost() ?: return

        socksProxyService.ensureRunning(controlHost)
    }

    /**
     * Executes a command with the complete lifecycle:
     * 1. Check requirements (may propagate a failure, exit the process, or run setup)
     * 2. Execute command (includes @PreExecute, execute(), @PostExecute via PicoCommand.call())
     * 3. Handle post-success actions
     *
     * Requirement checks and command execution share one try/catch so that a failure raised
     * while checking requirements (e.g. [RequiresProxy] failing to establish the tunnel) is
     * reported and converted to a non-zero exit code exactly like a failure inside the command
     * itself, rather than escaping uncaught.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeWithLifecycle(command: PicoCommand): Int {
        val exitCode =
            try {
                checkRequirements(command)
                command.call()
            } catch (e: Exception) {
                log.error(e) { "Command execution failed" }
                val causeChain =
                    generateSequence(e as Throwable) { it.cause }
                        .map { "${it::class.simpleName}: ${it.message}" }
                        .joinToString("\n  caused by: ")
                eventBus.emit(Event.Command.ExecutionError(causeChain))
                Constants.ExitCodes.ERROR
            }

        // 3. Post-success actions
        if (exitCode == 0) {
            handlePostSuccessActions(command)
        }

        return exitCode
    }

    /**
     * Checks and enforces command requirements based on annotations.
     * May exit the process if requirements cannot be satisfied.
     */
    private fun checkRequirements(command: PicoCommand) {
        val annotations = command::class.annotations

        // Clear any proxy failure recorded for a previous command before this one runs. Without
        // this, a Server/Repl session that keeps the process alive across many commands could
        // let a stale failure from an earlier command leak into a later one that never touched
        // the proxy at all.
        proxyAvailability.clear()

        // Check if the command requires profile setup
        if (annotations.any { it is RequireProfileSetup }) {
            if (!requirementCheckDeps.userConfigProvider.isSetup()) {
                // Run setup command with full lifecycle
                executeWithLifecycle(SetupProfile())

                // Show message and exit
                eventBus.emit(Event.Command.RetryInstruction)
                exitProcess(0)
            }
        }

        // Check if the command requires Docker
        if (annotations.any { it is RequireDocker }) {
            if (!checkDockerAvailability()) {
                eventBus.emit(Event.Command.DockerNotAvailable)
                eventBus.emit(
                    Event.Command.DockerSetupInstruction(
                        "Please ensure Docker is installed and running before executing this command.",
                    ),
                )
                exitProcess(1)
            }
        }

        // Check if the command requires an SSH key
        if (annotations.any { it is RequireSSHKey }) {
            if (!checkSSHKeyAvailability()) {
                eventBus.emit(Event.Command.SshKeyMissing(requirementCheckDeps.userConfigProvider.sshKeyPath))
                exitProcess(1)
            }
        }

        // Start the SOCKS5 proxy for commands that declare a dependency on it. A failure here
        // propagates — see ensureProxyRunning — and is caught by the try/catch in
        // executeWithLifecycle exactly like a failure inside the command itself.
        //
        // The single exception is a command whose annotation sets tolerateFailure = true
        // (currently only Status, see RequiresProxy's KDoc and design decision D9). For that
        // narrow, explicitly-declared case, the failure is recorded on ProxyAvailability instead
        // of propagating, and the command runs anyway — it is the command's own job to query
        // ProxyAvailability and degrade. This is not the blanket "proceeding without proxy"
        // swallow this change removed: it applies only to a command that opted in at its
        // declaration site, and only to this one failure.
        val requiresProxy = annotations.filterIsInstance<RequiresProxy>().firstOrNull()
        if (requiresProxy != null) {
            if (requiresProxy.tolerateFailure) {
                runCatching { ensureProxyRunning() }
                    .onFailure { e ->
                        log.warn(e) {
                            "Proxy failed to establish for ${command::class.simpleName}; " +
                                "this command tolerates the failure and will report degraded state"
                        }
                        proxyAvailability.recordFailure(e)
                    }
            } else {
                ensureProxyRunning()
            }
        }
    }

    /**
     * Handles post-success actions based on command annotations.
     */
    private fun handlePostSuccessActions(command: PicoCommand) {
        if (command::class.annotations.any { it is TriggerBackup }) {
            performIncrementalBackup()
        }
    }

    /**
     * Performs incremental backup of configuration files to S3.
     *
     * Computes hashes of all backup targets and uploads only files that have changed
     * since the last backup.
     */
    private fun performIncrementalBackup() {
        // Skip if no state file exists
        if (!clusterStateManager.exists()) {
            log.debug { "Skipping backup: no state file exists" }
            return
        }

        val state = clusterStateManager.load()

        // Skip if no S3 bucket configured
        if (state.s3Bucket.isNullOrBlank()) {
            log.debug { "Skipping backup: no S3 bucket configured" }
            return
        }

        backupRestoreService
            .backupChanged(context.workingDirectory.absolutePath, state)
            .onSuccess { result ->
                if (result.filesUploaded > 0) {
                    // Update state with new hashes
                    state.backupHashes = state.backupHashes + result.updatedHashes
                    clusterStateManager.save(state)
                    eventBus.emit(Event.Backup.IncrementalBackupComplete(result.filesUploaded))
                }
            }.onFailure { e ->
                log.warn(e) { "Incremental backup failed" }
                eventBus.emit(Event.Backup.IncrementalBackupFailed(e.message ?: "Unknown error"))
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkDockerAvailability(): Boolean =
        try {
            val dockerClient = requirementCheckDeps.dockerClientProvider.getDockerClient()
            // Try to list images as a simple health check
            dockerClient.listImages("", "")
            true
        } catch (e: Exception) {
            log.error(e) { "Docker availability check failed" }
            false
        }

    private fun checkSSHKeyAvailability(): Boolean = File(requirementCheckDeps.userConfigProvider.sshKeyPath).exists()

    /**
     * Handles VPC state reconstruction from environment variable.
     *
     * If EASY_DB_LAB_RESTORE_VPC is set, reconstructs state from the specified VPC ID
     * before executing any command. This allows recovery of state from an existing
     * AWS environment without needing command-line flags.
     */
    private fun handleVpcReconstructionFromEnv() {
        val vpcId = System.getenv("EASY_DB_LAB_RESTORE_VPC") ?: return

        backupRestoreService
            .restoreFromVpc(
                vpcId = vpcId,
                workingDirectory = context.workingDirectory.absolutePath,
                force = true, // Always force when using env var (explicit user action)
            ).onFailure { error ->
                log.error(error) { "Failed to restore from VPC: $vpcId" }
                throw error
            }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
