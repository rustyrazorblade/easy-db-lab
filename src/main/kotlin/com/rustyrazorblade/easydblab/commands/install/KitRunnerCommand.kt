package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.DashboardRef
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpointAddresses
import com.rustyrazorblade.easydblab.services.KitEndpointResolver
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.KitWorkloadProbe
import com.rustyrazorblade.easydblab.services.KubeconfigProxyResolver
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadPresence
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import picocli.CommandLine
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RequiresProxy
class KitRunnerCommand(
    private val kitName: String,
    private val kitDir: File,
    private val phaseName: String,
    private val kubeconfigProxyResolver: KubeconfigProxyResolver = KubeconfigProxyResolver(),
) : PicoBaseCommand() {
    @CommandLine.Option(
        names = ["--name"],
        description = ["Name passed to the phase script as BACKUP_NAME (default: timestamp)"],
    )
    var name: String = "backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"

    val runtimeArgValues: MutableMap<String, String> = mutableMapOf()
    private val grafanaDashboardService: GrafanaDashboardService by inject()
    private val workloadStepExecutor: WorkloadStepExecutor by inject()
    private val metricsRegistryService: MetricsRegistryService by inject()
    private val kitHookExecutor: KitHookExecutor by inject()
    private val kitEndpointResolver: KitEndpointResolver by inject()
    private val workspaceKubeconfig = File(kitDir.parentFile, Constants.K3s.LOCAL_KUBECONFIG)
    private val workloadProbe: KitWorkloadProbe by inject { parametersOf(workspaceKubeconfig.path) }

    private var processExitCode: Int = 0

    override fun execute() {
        val config = loadInstallConfig()

        val startIsGuarded = config?.collisionCheck?.guards(Constants.Kit.PHASE_START) == true
        if (phaseName == Constants.Kit.PHASE_START && startIsGuarded && isAlreadyRunning(requireNotNull(config))) {
            return
        }

        // Resolve the kubeconfig that local kubectl/helm shell steps will use. On a SOCKS-only
        // cluster this yields a temp copy carrying a `proxy-url` so those binaries route through
        // the tunnel; on Tailscale/no-proxy it returns the workspace kubeconfig unchanged. The
        // temp file (if any) is deleted when the block exits, on both success and failure.
        kubeconfigProxyResolver.resolve(workspaceKubeconfig).use { resolvedKubeconfig ->
            val augmentedEnv = buildAugmentedEnv(config, resolvedKubeconfig.path)
            val kitConfig = config ?: KitConfig(name = kitName)

            if (stopsBeforeUninstall(kitConfig)) {
                processExitCode = runPhase(Constants.Kit.PHASE_STOP, kitConfig, augmentedEnv)
                if (processExitCode != 0) return
            }

            when {
                hasPhase(kitConfig, phaseName) -> {
                    processExitCode = runPhase(phaseName, kitConfig, augmentedEnv)
                    if (processExitCode == 0) completePhase(kitConfig)
                }
                phaseName == Constants.Kit.PHASE_UNINSTALL -> removeKitDirectory()
                else -> error("No typed phase or script found for '$phaseName' in kit '$kitName'")
            }
        }
    }

    /**
     * Uninstalling a kit that is still running first runs its `stop` phase, stop wait included.
     * A kit's uninstall steps remove what `install` created — an operator, Keeper — not the
     * workload `start` created, whose objects would otherwise be orphaned, with finalizers that
     * a removed operator can no longer process.
     */
    private fun stopsBeforeUninstall(config: KitConfig): Boolean =
        phaseName == Constants.Kit.PHASE_UNINSTALL &&
            kitName in clusterState.runningKits &&
            hasPhase(config, Constants.Kit.PHASE_STOP)

    /** True when the kit declares [phase] as typed steps or ships a script for it. */
    private fun hasPhase(
        config: KitConfig,
        phase: String,
    ): Boolean = config.stepsForPhase(phase).isNotEmpty() || findScriptFile(phase) != null

    /**
     * Collision check for `start` (typed-install-steps: `collision-check: true`, or a phase map with
     * `start: true`, guards the start phase). Reports the kit's running objects and fails the command
     * when its runtime declaration finds its workload already in the cluster; a failed cluster query
     * fails the command.
     */
    private fun isAlreadyRunning(config: KitConfig): Boolean {
        val controlHost =
            clusterState.getControlHost()
                ?: error("No control node found in cluster state")
        return when (val presence = workloadProbe.find(kitName, config.runtime, controlHost).getOrThrow()) {
            is WorkloadPresence.Absent -> false
            is WorkloadPresence.Present -> {
                eventBus.emit(
                    Event.Kit.CollisionDetected(
                        kit = kitName,
                        phase = phaseName,
                        namespace = presence.namespace,
                        resources = presence.resources,
                    ),
                )
                processExitCode = Constants.ExitCodes.ERROR
                true
            }
        }
    }

    private fun buildAugmentedEnv(
        config: KitConfig?,
        kubeconfigPath: File,
    ): Map<String, String> {
        val argDefaults = config?.args?.associate { it.variable to it.default }.orEmpty()
        // Read installed arg values written by kit install, overriding the kit defaults.
        // This ensures phases like platform-pvs use the actual installed STORAGE_SIZE
        // (e.g. 100Gi) rather than the kit default (e.g. 10Ti), keeping PV capacity
        // consistent with the PVC request in the rendered manifest.
        val resolvedArgs = readResolvedArgs()
        val storageSize = resolvedArgs["STORAGE_SIZE"] ?: argDefaults["STORAGE_SIZE"] ?: ""
        val base =
            TemplateVariables
                .from(state = clusterState, kitName = kitName, storageSize = storageSize)
                .toMap()
        // Shell steps run from kitDir (e.g. clickhouse/), so a relative KUBECONFIG would resolve
        // to clickhouse/kubeconfig which doesn't exist. Use the absolute path of the kubeconfig
        // resolved for this command (the SOCKS-proxied temp copy when a tunnel is published).
        val absoluteKubeconfig = kubeconfigPath.absolutePath
        // Apply in order: argDefaults → resolvedArgs → cluster state (base) → KUBECONFIG → BACKUP_NAME.
        // argDefaults first so cluster-state values in base take precedence over kit defaults;
        // resolvedArgs overlays defaults with user-specified install-time values.
        val env = argDefaults + resolvedArgs + runtimeArgValues + base + ("KUBECONFIG" to absoluteKubeconfig)
        val targetVars =
            config
                ?.kitRefArg
                ?.let { resolvedArgs[it.variable]?.takeIf { v -> v.isNotBlank() } }
                ?.let { kitEndpointResolver.resolveTargetVars(File(kitDir.parentFile, it), clusterState) }
                .orEmpty()
        return env + ("BACKUP_NAME" to name) + targetVars
    }

    /**
     * Reads the resolved kit arg values persisted by [BaseInstallCommand.renderAndWrite] at
     * install time. Returns an empty map if the file does not exist (e.g., for kits installed
     * before this feature was added).
     */
    private fun readResolvedArgs(): Map<String, String> {
        val file = File(kitDir, Constants.Kit.RESOLVED_ARGS_FILE)
        if (!file.isFile) return emptyMap()
        return file
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val idx = line.indexOf('=')
                if (idx < 0) line to "" else line.substring(0, idx) to line.substring(idx + 1)
            }
    }

    override fun call(): Int {
        super.call()
        return processExitCode
    }

    private fun removeKitDirectory() {
        println("Warning: no uninstall phase configured for '$kitName' — removing local directory only")
        eventBus.emit(Event.Kit.ScriptStarted(kit = kitName, script = phaseName))
        kitDir.deleteRecursively()
        processExitCode = 0
        eventBus.emit(Event.Kit.ScriptFinished(kit = kitName, script = phaseName, exitCode = 0))
    }

    private fun loadInstallConfig(): KitConfig? {
        val configYaml = File(kitDir, Constants.Kit.CONFIG_FILE)
        if (!configYaml.isFile) return null
        return installConfigYaml.decodeFromString(KitConfig.serializer(), configYaml.readText())
    }

    private fun findScriptFile(phase: String): File? {
        val binDir = File(kitDir, "bin")
        if (!binDir.isDirectory) return null
        val withSuffix = File(binDir, "$phase.sh")
        if (withSuffix.isFile) return withSuffix
        val bare = File(binDir, phase)
        if (bare.isFile && bare.canExecute()) return bare
        return null
    }

    /**
     * Runs [phase] — its typed steps, or else its script, the same way for both — reports it,
     * and returns its exit code. A phase that succeeded still fails when it was a `stop` whose
     * workload did not leave (see [stoppedWorkloadIsGone]); without a control node there is
     * nothing to wait on.
     */
    private fun runPhase(
        phase: String,
        config: KitConfig,
        envVars: Map<String, String>,
    ): Int {
        eventBus.emit(Event.Kit.ScriptStarted(kit = kitName, script = phase))
        val typedSteps = config.stepsForPhase(phase)
        val stepsExitCode =
            if (typedSteps.isNotEmpty()) {
                executeTypedSteps(phase, typedSteps, envVars)
            } else {
                executeScript(requireNotNull(findScriptFile(phase)) { "No script for '$phase' in kit '$kitName'" }, envVars)
            }
        val controlHost = clusterState.getControlHost()
        val exitCode =
            when {
                stepsExitCode != 0 -> stepsExitCode
                controlHost == null || stoppedWorkloadIsGone(phase, config, controlHost) -> 0
                else -> Constants.ExitCodes.ERROR
            }
        eventBus.emit(Event.Kit.ScriptFinished(kit = kitName, script = phase, exitCode = exitCode))
        return exitCode
    }

    private fun executeTypedSteps(
        phase: String,
        steps: List<InstallStep>,
        envVars: Map<String, String>,
    ): Int {
        val controlHost =
            clusterState.getControlHost()
                ?: error("No control node found in cluster state")

        // The step executor has already reported a failed step as a typed event; rethrowing it
        // would have the command executor print it again as a raw exception.
        return workloadStepExecutor
            .execute(
                steps = steps,
                phase = phase,
                context =
                    StepExecutionContext(
                        kitName = kitName,
                        controlHost = controlHost,
                        clusterState = clusterState,
                        variables = envVars,
                        kitDir = kitDir,
                    ),
            ).fold(
                onSuccess = { 0 },
                onFailure = { e ->
                    log.debug(e) { "$kitName $phase failed" }
                    Constants.ExitCodes.ERROR
                },
            )
    }

    /**
     * Completes the command's phase after it succeeded: removes the kit directory after
     * `uninstall`, and runs the post-phase actions, which need the control node.
     */
    private fun completePhase(config: KitConfig) {
        if (phaseName == Constants.Kit.PHASE_UNINSTALL) {
            kitDir.deleteRecursively()
        }
        val controlHost =
            clusterState.getControlHost() ?: run {
                log.warn { "No control node found; skipping post-phase actions for $kitName" }
                return
            }
        handlePostPhase(config, controlHost)
    }

    /**
     * After a successful `stop` or `uninstall` of a kit that declares a `runtime`, waits for the
     * kit's pods to leave the cluster. Deleting a StatefulSet, Deployment or operator resource,
     * scaling a Deployment to zero, or `helm uninstall` (Presto, Trino), returns before its pods
     * have terminated, so without the wait the phase would return with the workload still
     * running, and a collision-checked `start` right after would find it and be refused. A kit
     * with no runtime declares no selector to wait on. Returns false, after reporting what is
     * left, when the workload outlives the wait, and false, after reporting the phase as
     * unverified, when the cluster cannot be queried: the steps already ran, so the caller still
     * reports the phase finished.
     */
    private fun stoppedWorkloadIsGone(
        phase: String,
        config: KitConfig,
        controlHost: ClusterHost,
    ): Boolean {
        if (phase !in PHASES_THAT_REMOVE_THE_WORKLOAD || config.runtime == null) return true
        val remaining =
            workloadProbe.awaitGone(kitName, config.runtime, controlHost).getOrElse { e ->
                log.warn(e) { "Could not confirm $kitName's workload left the cluster after $phase" }
                eventBus.emit(
                    Event.Kit.StopUnverified(kit = kitName, reason = e.message ?: e.javaClass.simpleName, phase = phase),
                )
                return false
            }
        return when (remaining) {
            is WorkloadPresence.Absent -> true
            is WorkloadPresence.Present -> {
                eventBus.emit(
                    Event.Kit.StopIncomplete(
                        kit = kitName,
                        namespace = remaining.namespace,
                        resources = remaining.resources,
                        phase = phase,
                    ),
                )
                false
            }
        }
    }

    private fun executeScript(
        scriptFile: File,
        envVars: Map<String, String>,
    ): Int =
        ProcessBuilder(scriptFile.absolutePath)
            .directory(context.workingDirectory)
            .inheritIO()
            .also { pb -> pb.environment().putAll(envVars) }
            .start()
            .waitFor()

    private fun handlePostPhase(
        config: KitConfig,
        controlHost: ClusterHost,
    ) {
        when (phaseName) {
            Constants.Kit.PHASE_START -> {
                clusterStateManager.addRunningWorkload(kitName)
                kitHookExecutor.firePostKitStart(kitName)
                val resolvedArgs = readResolvedArgs()
                val metricsPortOverride = resolvedArgs["METRICS_PORT"]?.toIntOrNull()
                // A METRICS_PORT override is an instance's own metrics NodePort, so it only
                // applies to a static localhost job; a pod-discovered target keeps its container
                // port. Its pod-selector names this instance through ${KIT_NAME}.
                val scrapeTargets =
                    config.metrics.filterIsInstance<KitMetrics.Scrape>().map { target ->
                        when {
                            target.podSelector.isNotBlank() ->
                                target.copy(podSelector = target.podSelector.replace("\${KIT_NAME}", kitName))
                            metricsPortOverride != null -> target.copy(port = metricsPortOverride)
                            else -> target
                        }
                    }
                if (scrapeTargets.isNotEmpty()) {
                    metricsRegistryService
                        .register(
                            controlHost = controlHost,
                            kitName = kitName,
                            targets = scrapeTargets,
                        ).onFailure { e -> log.warn(e) { "Failed to register metrics for $kitName" } }
                }
                installDashboards(config.dashboards)
                reportEndpoints(config)
            }
            Constants.Kit.PHASE_STOP -> releaseWorkload(controlHost)
            // Uninstalling a kit that is still running removes it just as `stop` would. Its
            // metrics ConfigMaps carry `easydblab.com/kit`, which the kit's own label-scoped
            // deletes (`easydblab/kit`) do not match, so without this they, their OTel scrape
            // jobs and the runningKits entry would outlive the kit.
            Constants.Kit.PHASE_UNINSTALL -> if (kitName in clusterState.runningKits) releaseWorkload(controlHost)
        }
    }

    /**
     * Records that [kitName] no longer runs: drops it from `runningKits`, fires the other running
     * kits' post-stop hooks, and deregisters its metrics, which also resyncs the OTel collector.
     */
    private fun releaseWorkload(controlHost: ClusterHost) {
        clusterStateManager.removeRunningWorkload(kitName)
        kitHookExecutor.firePostKitStop(kitName)
        metricsRegistryService
            .deregister(controlHost = controlHost, kitName = kitName)
            .onFailure { e -> log.warn(e) { "Failed to deregister metrics for $kitName" } }
    }

    /**
     * Reports where to connect: every declared endpoint at its node's private IP. The kit has
     * already started, so a failure here is logged and never turns the start into a failure.
     */
    private fun reportEndpoints(config: KitConfig) {
        runCatching {
            val resolved = KitEndpointAddresses.resolve(config.endpoints, clusterState.hosts)
            if (resolved.isNotEmpty()) {
                eventBus.emit(
                    Event.Kit.EndpointsAvailable(
                        kit = kitName,
                        endpoints = resolved.map { KitEndpointAddresses.toEndpointAddress(it) },
                    ),
                )
            }
        }.onFailure { e -> log.warn(e) { "Failed to report endpoints for $kitName" } }
    }

    private fun installDashboards(dashboards: List<DashboardRef>) {
        // A telemetry-redirect cluster has no local Grafana — dashboards live on the external stack.
        // Skip cleanly so a successful `start` is not turned into a failure by a missing Grafana.
        if (clusterState.initConfig?.telemetryRedirect != null) {
            log.info { "Telemetry redirect is active; skipping kit dashboard installation for $kitName." }
            return
        }

        val controlHost =
            clusterState.getControlHost() ?: run {
                log.warn { "No control node found; skipping dashboard installation for $kitName" }
                return
            }

        if (dashboards.isNotEmpty()) {
            dashboards.forEach { dashRef ->
                val file = File(kitDir, dashRef.path)
                if (!file.isFile) {
                    log.warn { "Dashboard file not found: ${file.absolutePath}" }
                    return@forEach
                }
                grafanaDashboardService
                    .installDashboardFromFile(file = file, controlHost = controlHost, folderName = kitName)
                    .onFailure { log.warn(it) { "Failed to install dashboard ${file.name}" } }
            }
        } else {
            val dashboardsDir = File(kitDir, "dashboards")
            if (!dashboardsDir.isDirectory) return
            dashboardsDir
                .listFiles { _, name -> name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
                .forEach { file ->
                    grafanaDashboardService
                        .installDashboardFromFile(file = file, controlHost = controlHost, folderName = kitName)
                        .onFailure { log.warn(it) { "Failed to install dashboard ${file.name}" } }
                }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** The phases that take the kit's workload out of the cluster, and so wait for its pods to go. */
        private val PHASES_THAT_REMOVE_THE_WORKLOAD = setOf(Constants.Kit.PHASE_STOP, Constants.Kit.PHASE_UNINSTALL)
    }
}
