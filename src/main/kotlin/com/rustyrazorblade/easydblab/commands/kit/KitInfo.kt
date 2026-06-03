package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.commands.install.BaseInstallCommand
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/**
 * Shows detailed information about a kit: description, version, type,
 * configurable args, exposed endpoints, available lifecycle commands, and hooks.
 *
 * Analogous to `brew info` or `apt show` — lets users inspect a kit before installing.
 * Uses println() directly because this is a read-only display command with no
 * associated domain events.
 */
@Command(
    name = "info",
    description = ["Show details about an available kit before installing"],
    mixinStandardHelpOptions = true,
)
class KitInfo : BaseInstallCommand() {
    private val scanner: KitCommandScanner by inject()

    @Parameters(
        index = "0",
        description = ["Name of the kit to inspect (e.g. clickhouse, presto)"],
    )
    lateinit var kitName: String

    override fun execute() {
        val source = resolver.resolve(kitName)
        val config =
            resolver.loadInstallConfig(source)
                ?: error("No kit.yaml found for '$kitName'")
        val templateFiles = resolver.listTemplateFiles(source).map { it.name }
        val annotatedCommands = scanner.forKit(kitName).map { it.name to it.description }
        println(buildInfoText(config, templateFiles, annotatedCommands))
    }

    companion object {
        private val phaseDescriptions =
            mapOf(
                "start" to "Start the workload",
                "stop" to "Stop the workload",
                "backup" to "Back up workload data",
                "restore" to "Restore workload data",
                "uninstall" to "Uninstall the kit and remove all resources",
                "status" to "Show running state and connection endpoints",
            )

        /**
         * Builds the sorted list of (name, description) pairs for all commands available
         * in this kit — both script-based lifecycle commands and @KitCommand Kotlin commands.
         *
         * Exposed for testing so ordering assertions can target the data directly rather
         * than parsing rendered output.
         */
        fun buildCommandList(
            config: KitConfig,
            scriptCommandNames: Set<String>,
            annotatedCommands: List<Pair<String, String>>,
        ): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()

            // Known lifecycle phases from config declarations or bin scripts
            val phases = listOf("start", "stop", "backup", "restore", "uninstall", "status")
            for (phase in phases) {
                val present =
                    when (phase) {
                        "start" -> config.start.isNotEmpty() || phase in scriptCommandNames
                        "stop" -> config.stop.isNotEmpty() || phase in scriptCommandNames
                        "backup" -> config.backup.isNotEmpty() || phase in scriptCommandNames
                        "restore" -> config.restore.isNotEmpty() || phase in scriptCommandNames
                        "uninstall" -> config.uninstall.isNotEmpty() || phase in scriptCommandNames
                        // status is provided by the kit runner for every installed kit
                        "status" -> true
                        else -> phase in scriptCommandNames
                    }
                if (present) result.add(phase to (phaseDescriptions[phase] ?: "(script)"))
            }

            // Remaining script commands not covered by the known phases above
            val covered = result.map { it.first }.toSet()
            scriptCommandNames
                .filter { it !in covered }
                .forEach { result.add(it to "(script)") }

            // Kotlin commands contributed via @KitCommand
            result.addAll(annotatedCommands)

            return result.sortedBy { it.first }
        }

        /**
         * Builds the human-readable info text for a kit. Extracted for testability.
         */
        fun buildInfoText(
            config: KitConfig,
            templateFiles: List<String>,
            annotatedCommands: List<Pair<String, String>> = emptyList(),
        ): String {
            val scriptCommandNames =
                templateFiles
                    .filter { it.startsWith("bin/") }
                    .map {
                        it
                            .removePrefix("bin/")
                            .removeSuffix(".sh.template")
                            .removeSuffix(".template")
                            .removeSuffix(".sh")
                    }.toSet()

            val commands = buildCommandList(config, scriptCommandNames, annotatedCommands)

            return buildString {
                append(config.name)
                if (config.version.isNotEmpty()) append(" (${config.version})")
                val typeName = config.type?.name?.lowercase() ?: ""
                if (typeName.isNotEmpty()) append("  [$typeName]")
                appendLine()
                if (config.description.isNotEmpty()) {
                    appendLine("  ${config.description}")
                }
                if (config.args.isNotEmpty()) {
                    appendLine()
                    appendLine("Args:")
                    appendArgs(config.args)
                }
                if (config.endpoints.isNotEmpty()) {
                    appendLine()
                    appendLine("Endpoints:")
                    appendEndpoints(config.endpoints)
                }
                if (commands.isNotEmpty()) {
                    appendLine()
                    appendLine("Commands:")
                    appendCommands(config.name, commands)
                }
                val startHook = config.hooks?.postWorkloadStart?.script
                val stopHook = config.hooks?.postWorkloadStop?.script
                if (startHook != null || stopHook != null) {
                    appendLine()
                    appendLine("Hooks:")
                    startHook?.let { appendLine("  post-workload-start: $it") }
                    stopHook?.let { appendLine("  post-workload-stop:  $it") }
                }
            }.trimEnd()
        }

        private fun StringBuilder.appendArgs(args: List<KitArgSpec>) {
            val flagWidth = args.maxOf { "${it.flag} ${it.type.name}".length }
            for (arg in args) {
                val flagCol = "${arg.flag} ${arg.type.name}".padEnd(flagWidth)
                val detail =
                    buildString {
                        append(arg.description)
                        if (arg.default.isNotEmpty()) append("  (default: ${arg.default})")
                        if (arg.required) append("  [required]")
                    }
                appendLine("  $flagCol  $detail")
            }
        }

        private fun StringBuilder.appendEndpoints(endpoints: List<KitEndpoint>) {
            val nameWidth = endpoints.maxOf { it.name.length }
            val nodeTypeWidth = endpoints.maxOf { it.nodeType.length }
            for (ep in endpoints) {
                appendLine(
                    "  ${ep.name.padEnd(nameWidth)}  ${ep.nodeType.padEnd(nodeTypeWidth)}  :${ep.port}  ${ep.type.name.lowercase()}",
                )
            }
        }

        private fun StringBuilder.appendCommands(
            kitName: String,
            commands: List<Pair<String, String>>,
        ) {
            val cmdWidth = commands.maxOf { "$kitName ${it.first}".length }
            for ((cmd, desc) in commands) {
                val invocation = "$kitName $cmd"
                appendLine("  ${invocation.padEnd(cmdWidth)}  $desc")
            }
        }
    }
}
