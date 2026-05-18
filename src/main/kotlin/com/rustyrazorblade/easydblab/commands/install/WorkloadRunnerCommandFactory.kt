package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.WorkloadInstallConfig
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.File
import java.util.concurrent.Callable

class WorkloadRunnerCommandFactory {
    fun buildWorkloadGroup(
        workloadName: String,
        workloadDir: File,
    ): CommandLine {
        val groupCommand = WorkloadGroupCommand()
        val groupSpec = CommandSpec.wrapWithoutInspection(groupCommand).name(workloadName)
        groupSpec.usageMessage().description("Workload commands for $workloadName")
        groupSpec.mixinStandardHelpOptions(true)
        groupCommand.spec = groupSpec
        val groupCL = CommandLine(groupSpec)

        val phases = collectPhases(workloadDir)
        phases.sorted().forEach { phaseName ->
            groupCL.addSubcommand(phaseName, buildPhaseCommand(workloadName, workloadDir, phaseName))
        }

        return groupCL
    }

    private fun collectPhases(workloadDir: File): Set<String> {
        val scriptPhases = collectScriptPhases(workloadDir)
        val typedPhases = collectTypedPhases(workloadDir)
        return scriptPhases + typedPhases
    }

    private fun collectScriptPhases(workloadDir: File): Set<String> {
        val binDir = File(workloadDir, "bin")
        if (!binDir.isDirectory) return emptySet()
        return binDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && (it.canExecute() || it.name.endsWith(".sh")) }
            .map { it.name.removeSuffix(".sh") }
            .toSet()
    }

    private fun collectTypedPhases(workloadDir: File): Set<String> {
        val installYaml = File(workloadDir, "install.yaml")
        if (!installYaml.isFile) return emptySet()
        return try {
            val config = installConfigYaml.decodeFromString(WorkloadInstallConfig.serializer(), installYaml.readText())
            buildSet {
                if (config.install.isNotEmpty()) add("install")
                if (config.start.isNotEmpty()) add("start")
                if (config.stop.isNotEmpty()) add("stop")
                if (config.uninstall.isNotEmpty()) add("uninstall")
            }
        } catch (e: Exception) {
            log.debug(e) { "Failed to parse install.yaml in ${workloadDir.path}" }
            emptySet()
        }
    }

    private fun buildPhaseCommand(
        workloadName: String,
        workloadDir: File,
        phaseName: String,
    ): CommandLine {
        val command = WorkloadRunnerCommand(workloadName, workloadDir, phaseName)
        val spec = CommandSpec.wrapWithoutInspection(command).name(phaseName)
        spec.usageMessage().description("Run $workloadName/$phaseName")
        spec.mixinStandardHelpOptions(true)
        return CommandLine(spec)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

class WorkloadGroupCommand : Callable<Int> {
    lateinit var spec: CommandSpec

    override fun call(): Int {
        spec.commandLine().usage(System.out)
        return 0
    }
}
