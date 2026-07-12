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
import com.rustyrazorblade.easydblab.services.KitEndpointResolver
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.KitMetrics
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.StepExecutionContext
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RequiresProxy
class KitRunnerCommand(
    private val kitName: String,
    private val kitDir: File,
    private val phaseName: String,
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

    private var processExitCode: Int = 0

    override fun execute() {
        val config = loadInstallConfig()
        val typedSteps = config?.stepsForPhase(phaseName)?.takeIf { it.isNotEmpty() }
        val augmentedEnv = buildAugmentedEnv(config)

        if (typedSteps != null) {
            executeTypedPhase(config, typedSteps, augmentedEnv)
        } else {
            val scriptFile = findScriptFile()
            when {
                scriptFile != null -> executeScript(scriptFile, augmentedEnv, config ?: KitConfig(name = kitName))
                phaseName == Constants.Kit.PHASE_UNINSTALL -> removeKitDirectory()
                else -> error("No typed phase or script found for '$phaseName' in kit '$kitName'")
            }
        }
    }

    private fun buildAugmentedEnv(config: KitConfig?): Map<String, String> {
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
        // Shell steps run from kitDir (e.g. clickhouse/), so a relative KUBECONFIG
        // would resolve to clickhouse/kubeconfig which doesn't exist. Use the absolute path.
        val absoluteKubeconfig = File(kitDir.parentFile, Constants.K3s.LOCAL_KUBECONFIG).absolutePath
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

    private fun findScriptFile(): File? {
        val binDir = File(kitDir, "bin")
        if (!binDir.isDirectory) return null
        val withSuffix = File(binDir, "$phaseName.sh")
        if (withSuffix.isFile) return withSuffix
        val bare = File(binDir, phaseName)
        if (bare.isFile && bare.canExecute()) return bare
        return null
    }

    private fun executeTypedPhase(
        config: KitConfig,
        steps: List<InstallStep>,
        envVars: Map<String, String>,
    ) {
        eventBus.emit(Event.Kit.ScriptStarted(kit = kitName, script = phaseName))

        val controlHost =
            clusterState.getControlHost()
                ?: error("No control node found in cluster state")

        val result =
            workloadStepExecutor.execute(
                steps = steps,
                phase = phaseName,
                context =
                    StepExecutionContext(
                        kitName = kitName,
                        controlHost = controlHost,
                        clusterState = clusterState,
                        variables = envVars,
                        kitDir = kitDir,
                    ),
            )

        result
            .onSuccess {
                processExitCode = 0
                eventBus.emit(Event.Kit.ScriptFinished(kit = kitName, script = phaseName, exitCode = 0))
                if (phaseName == Constants.Kit.PHASE_UNINSTALL) {
                    kitDir.deleteRecursively()
                }
                handlePostPhase(config, controlHost)
            }.onFailure { error ->
                eventBus.emit(Event.Kit.ScriptFinished(kit = kitName, script = phaseName, exitCode = 1))
                throw error
            }
    }

    private fun executeScript(
        scriptFile: File,
        envVars: Map<String, String>,
        config: KitConfig,
    ) {
        eventBus.emit(Event.Kit.ScriptStarted(kit = kitName, script = phaseName))

        val process =
            ProcessBuilder(scriptFile.absolutePath)
                .directory(context.workingDirectory)
                .inheritIO()
                .also { pb -> pb.environment().putAll(envVars) }
                .start()

        processExitCode = process.waitFor()
        eventBus.emit(
            Event.Kit.ScriptFinished(kit = kitName, script = phaseName, exitCode = processExitCode),
        )

        if (processExitCode == 0) {
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
    }

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
                val scrapeTargets =
                    config.metrics.filterIsInstance<KitMetrics.Scrape>().map { target ->
                        if (metricsPortOverride != null) target.copy(port = metricsPortOverride) else target
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
            }
            Constants.Kit.PHASE_STOP -> {
                clusterStateManager.removeRunningWorkload(kitName)
                kitHookExecutor.firePostKitStop(kitName)
                metricsRegistryService
                    .deregister(controlHost = controlHost, kitName = kitName)
                    .onFailure { e -> log.warn(e) { "Failed to deregister metrics for $kitName" } }
            }
        }
    }

    private fun installDashboards(dashboards: List<DashboardRef>) {
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
    }
}
