package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.File
import java.util.concurrent.Callable

class KitRunnerCommandFactory {
    fun buildKitGroup(
        kitName: String,
        kitDir: File,
    ): CommandLine {
        val groupCommand = KitGroupCommand()
        val groupSpec = CommandSpec.wrapWithoutInspection(groupCommand).name(kitName)
        groupSpec.usageMessage().description("Kit commands for $kitName")
        groupSpec.mixinStandardHelpOptions(true)
        groupCommand.spec = groupSpec
        val groupCL = CommandLine(groupSpec)

        val installConfig = loadInstallConfig(kitName, kitDir)
        val phases = collectPhases(kitDir, installConfig)
        phases.sorted().forEach { phaseName ->
            groupCL.addSubcommand(phaseName, buildPhaseCommand(kitName, kitDir, phaseName))
        }
        groupCL.addSubcommand("status", buildStatusCommand(kitName, kitDir, installConfig))

        return groupCL
    }

    private fun loadInstallConfig(
        kitName: String,
        kitDir: File,
    ): KitConfig {
        val configYaml = File(kitDir, Constants.Kit.CONFIG_FILE)
        if (!configYaml.isFile) return KitConfig(name = kitName)
        return try {
            installConfigYaml.decodeFromString(KitConfig.serializer(), configYaml.readText())
        } catch (e: Exception) {
            log.warn(e) { "${Constants.Kit.CONFIG_FILE} in ${kitDir.path} could not be parsed — using empty config" }
            KitConfig(name = kitName)
        }
    }

    private fun collectPhases(
        kitDir: File,
        installConfig: KitConfig,
    ): Set<String> = (collectScriptPhases(kitDir) + collectTypedPhases(installConfig)) - "status"

    private fun collectScriptPhases(kitDir: File): Set<String> {
        val binDir = File(kitDir, "bin")
        if (!binDir.isDirectory) return emptySet()
        return binDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && (it.canExecute() || it.name.endsWith(".sh")) }
            .map { it.name.removeSuffix(".sh") }
            .toSet()
    }

    private fun collectTypedPhases(installConfig: KitConfig): Set<String> =
        buildSet {
            // install phase is run by `easy-db-lab install <kit>` — not exposed here
            if (installConfig.start.isNotEmpty()) add("start")
            if (installConfig.stop.isNotEmpty()) add("stop")
            if (installConfig.uninstall.isNotEmpty()) add("uninstall")
            if (installConfig.backup.isNotEmpty()) add("backup")
            if (installConfig.restore.isNotEmpty()) add("restore")
        }

    private fun buildPhaseCommand(
        kitName: String,
        kitDir: File,
        phaseName: String,
    ): CommandLine {
        val command = KitRunnerCommand(kitName, kitDir, phaseName)
        val spec = CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory()).name(phaseName)
        spec.usageMessage().description("Run $kitName/$phaseName")
        spec.mixinStandardHelpOptions(true)
        return CommandLine(spec)
    }

    fun buildStatusCommand(
        kitName: String,
        kitDir: File,
        installConfig: KitConfig,
    ): CommandLine {
        val command = KitStatusCommand(kitName, installConfig)
        val spec = CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory()).name("status")
        spec.usageMessage().description("Show running state and connection endpoints for $kitName")
        spec.mixinStandardHelpOptions(true)
        return CommandLine(spec)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

class KitGroupCommand : Callable<Int> {
    lateinit var spec: CommandSpec

    override fun call(): Int {
        spec.commandLine().usage(System.out)
        return 0
    }
}
