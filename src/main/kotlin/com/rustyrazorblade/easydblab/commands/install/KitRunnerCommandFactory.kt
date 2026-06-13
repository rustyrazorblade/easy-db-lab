package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.kit.KitSqlCommand
import com.rustyrazorblade.easydblab.services.KitCapability
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.installConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.File
import java.util.concurrent.Callable

/**
 * Builds PicoCLI subcommand groups for installed kits.
 *
 * For each kit directory found in the cluster workspace, this factory constructs a
 * [CommandLine] group containing lifecycle phase commands (start, stop, etc.), a status
 * command, and any commands generated from the kit's declared capabilities (e.g. `sql`).
 *
 * Capability commands are registered alongside script-based and typed-phase commands —
 * they are additive and do not conflict.
 */
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

        buildCapabilityCommands(kitName, installConfig).forEach { (name, cmdLine) ->
            if (name !in groupCL.subcommands.keys) {
                groupCL.addSubcommand(name, cmdLine)
            }
        }

        if (File(kitDir, "extensions.yaml").isFile) {
            groupCL.addSubcommand("extensions", buildExtensionsCommand(kitDir))
        }

        return groupCL
    }

    /**
     * Builds a [CommandLine] for each recognised capability declared in the kit config.
     * Unknown capability types are logged and skipped — kits with future capability types
     * remain functional; only the unknown command is absent.
     */
    private fun buildCapabilityCommands(
        kitName: String,
        kitConfig: KitConfig,
    ): List<Pair<String, CommandLine>> =
        kitConfig.capabilities.mapNotNull { cap ->
            when (cap.type) {
                "sql" -> buildSqlCapabilityCommand(kitName, kitConfig, cap)
                else -> {
                    log.debug { "Kit '$kitName': unknown capability type '${cap.type}', skipping" }
                    null
                }
            }
        }

    private fun buildSqlCapabilityCommand(
        kitName: String,
        kitConfig: KitConfig,
        cap: KitCapability,
    ): Pair<String, CommandLine>? {
        val jdbcEndpoint = kitConfig.endpoints.firstOrNull { it.type == KitEndpoint.EndpointType.JDBC }
        if (jdbcEndpoint == null) {
            log.warn { "Kit '$kitName' declares sql capability but has no jdbc endpoint — skipping sql command" }
            return null
        }
        val (commandName, description) = cap.commandEntry(kitName)
        val command =
            KitSqlCommand(
                kitName = kitName,
                endpoint = jdbcEndpoint,
                user = cap.user,
                driverClass = cap.driverClass,
            )
        val spec = CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory()).name(commandName)
        spec.usageMessage().description(description)
        spec.mixinStandardHelpOptions(true)
        return commandName to CommandLine(spec)
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

    private fun buildExtensionsCommand(kitDir: File): CommandLine {
        val command = KitExtensionsCommand(kitDir)
        val spec = CommandLine.Model.CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory())
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

/** Root command for a kit group — prints usage when invoked with no subcommand. */
class KitGroupCommand : Callable<Int> {
    lateinit var spec: CommandSpec

    override fun call(): Int {
        spec.commandLine().usage(System.out)
        return 0
    }
}
