package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.kernel.PicoCommand
import org.koin.core.component.KoinComponent
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Displays the full command tree with all subcommands and options.
 *
 * Each command appears once, under its name, with any aliases named beside it; picocli registers
 * a command under every alias, so walking the subcommand map as-is lists it once per name. Option
 * placeholders come from picocli's own label renderer, so the tree shows `--size=<size>` exactly
 * as `--help` does and shows no placeholder for a flag.
 */
@Command(
    name = "commands",
    description = ["Display all commands, subcommands, and options"],
)
class Commands :
    PicoCommand,
    KoinComponent {
    @Spec
    lateinit var spec: CommandSpec

    override fun execute() {
        // Navigate to root command
        var root = spec.commandLine()
        while (root.parent != null) {
            root = root.parent
        }

        printCommandTree(root, 0)
    }

    private fun printCommandTree(
        cmd: CommandLine,
        depth: Int,
    ) {
        val spec = cmd.commandSpec
        val indent = "  ".repeat(depth)

        // Print command name, aliases, and description
        val description = spec.usageMessage().description().firstOrNull() ?: ""
        val aliases = spec.aliases().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " (alias: ", postfix = ")") ?: ""
        println("$indent${spec.name()}$aliases - $description")
        val labelRenderer = CommandLine.Help(spec).createDefaultParamLabelRenderer()

        // Skip hidden options and the standard help/version mixin flags.
        // usageHelp() covers --help; versionHelp() covers --version/-V from the mixin.
        // Filtering by predicate (not name) ensures kit args named --version are preserved.
        val options =
            spec.options().filter { opt ->
                !opt.hidden() && !opt.usageHelp() && !opt.versionHelp()
            }

        for (opt in options) {
            val optDesc = opt.description().firstOrNull() ?: ""
            val names = opt.names().joinToString(", ")
            val paramLabel = labelRenderer.renderParameterLabel(opt, CommandLine.Help.Ansi.OFF, emptyList()).toString()
            val required = if (opt.required()) " (required)" else ""
            println("$indent    $names$paramLabel$required - $optDesc")
        }

        // Print positional parameters
        for (param in spec.positionalParameters()) {
            if (!param.hidden()) {
                val paramDesc = param.description().firstOrNull() ?: ""
                val required = if (param.required()) " (required)" else ""
                println("$indent    <${param.paramLabel()}>$required - $paramDesc")
            }
        }

        // Recursively print subcommands, once each: the map holds one entry per name and alias.
        val subcommands =
            cmd.subcommands.values
                .distinct()
                .filter { !it.commandSpec.usageMessage().hidden() }
                .sortedBy { it.commandName }

        for (subCmd in subcommands) {
            printCommandTree(subCmd, depth + 1)
        }
    }
}
