package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.DashboardRef
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.MetricsRegistryService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import com.rustyrazorblade.easydblab.services.WorkloadInstallConfig
import com.rustyrazorblade.easydblab.services.WorkloadMetrics
import com.rustyrazorblade.easydblab.services.WorkloadStepExecutor
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import java.io.File

class WorkloadRunnerCommand(
    private val workloadName: String,
    private val workloadDir: File,
    private val phaseName: String,
) : PicoBaseCommand() {
    private val grafanaDashboardService: GrafanaDashboardService by inject()
    private val workloadStepExecutor: WorkloadStepExecutor by inject()
    private val metricsRegistryService: MetricsRegistryService by inject()

    private var processExitCode: Int = 0

    override fun execute() {
        val config = loadInstallConfig()
        val typedSteps = config?.stepsForPhase(phaseName)?.takeIf { it.isNotEmpty() }

        if (typedSteps != null) {
            executeTypedPhase(config, typedSteps)
        } else {
            val scriptFile =
                findScriptFile()
                    ?: error("No typed phase or script found for '$phaseName' in workload '$workloadName'")
            executeScript(scriptFile)
        }
    }

    override fun call(): Int {
        super.call()
        return processExitCode
    }

    private fun loadInstallConfig(): WorkloadInstallConfig? {
        val installYaml = File(workloadDir, "install.yaml")
        if (!installYaml.isFile) return null
        return try {
            installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), installYaml.readText())
        } catch (e: Exception) {
            log.debug(e) { "Failed to parse install.yaml in ${workloadDir.path}" }
            null
        }
    }

    private fun findScriptFile(): File? {
        val binDir = File(workloadDir, "bin")
        if (!binDir.isDirectory) return null
        val withSuffix = File(binDir, "$phaseName.sh")
        if (withSuffix.isFile) return withSuffix
        val bare = File(binDir, phaseName)
        if (bare.isFile && bare.canExecute()) return bare
        return null
    }

    private fun executeTypedPhase(
        config: WorkloadInstallConfig,
        steps: List<InstallStep>,
    ) {
        eventBus.emit(Event.Workload.ScriptStarted(workload = workloadName, script = phaseName))

        val variables =
            TemplateVariables
                .from(state = clusterState, workloadName = workloadName, storageSize = "")
                .toMap()

        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state")

        val result =
            workloadStepExecutor.execute(
                steps = steps,
                workloadName = workloadName,
                phase = phaseName,
                controlHost = controlHost,
                clusterState = clusterState,
                variables = variables,
                workloadDir = workloadDir,
            )

        result
            .onSuccess {
                processExitCode = 0
                eventBus.emit(Event.Workload.ScriptFinished(workload = workloadName, script = phaseName, exitCode = 0))
                when (phaseName) {
                    "start" -> {
                        val scrape = config.metrics as? WorkloadMetrics.Scrape
                        if (scrape != null) {
                            metricsRegistryService
                                .register(
                                    controlHost = controlHost,
                                    workloadName = workloadName,
                                    port = scrape.port,
                                    path = scrape.path,
                                ).onFailure { e -> log.warn(e) { "Failed to register metrics for $workloadName" } }
                        }
                        installDashboards(config.dashboards)
                    }
                    "stop" -> {
                        metricsRegistryService
                            .deregister(controlHost = controlHost, workloadName = workloadName)
                            .onFailure { e -> log.warn(e) { "Failed to deregister metrics for $workloadName" } }
                    }
                }
            }.onFailure { error ->
                processExitCode = 1
                eventBus.emit(Event.Workload.ScriptFinished(workload = workloadName, script = phaseName, exitCode = 1))
                throw error
            }
    }

    private fun executeScript(scriptFile: File) {
        eventBus.emit(Event.Workload.ScriptStarted(workload = workloadName, script = phaseName))

        val envVars =
            TemplateVariables
                .from(state = clusterState, workloadName = workloadName, storageSize = "")
                .toMap()

        val process =
            ProcessBuilder(scriptFile.absolutePath)
                .directory(context.workingDirectory)
                .inheritIO()
                .also { pb -> pb.environment().putAll(envVars) }
                .start()

        processExitCode = process.waitFor()
        eventBus.emit(
            Event.Workload.ScriptFinished(workload = workloadName, script = phaseName, exitCode = processExitCode),
        )

        if (phaseName == "start" && processExitCode == 0) {
            installDashboards(emptyList())
        }
    }

    private fun installDashboards(dashboards: List<DashboardRef>) {
        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull() ?: run {
                log.warn { "No control node found; skipping dashboard installation for $workloadName" }
                return
            }

        if (dashboards.isNotEmpty()) {
            dashboards.forEach { dashRef ->
                val file = File(workloadDir, dashRef.path)
                if (!file.isFile) {
                    log.warn { "Dashboard file not found: ${file.absolutePath}" }
                    return@forEach
                }
                grafanaDashboardService
                    .installDashboardFromFile(file = file, controlHost = controlHost)
                    .onFailure { log.warn(it) { "Failed to install dashboard ${file.name}" } }
            }
        } else {
            val dashboardsDir = File(workloadDir, "dashboards")
            if (!dashboardsDir.isDirectory) return
            dashboardsDir
                .listFiles { _, name -> name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
                .forEach { file ->
                    grafanaDashboardService
                        .installDashboardFromFile(file = file, controlHost = controlHost)
                        .onFailure { log.warn(it) { "Failed to install dashboard ${file.name}" } }
                }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

private fun WorkloadInstallConfig.stepsForPhase(phaseName: String): List<InstallStep> =
    when (phaseName) {
        "install" -> install
        "start" -> start
        "stop" -> stop
        "uninstall" -> uninstall
        else -> emptyList()
    }
