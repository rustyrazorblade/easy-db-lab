package com.rustyrazorblade.easydblab.commands

import org.koin.core.component.KoinComponent
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Displays the full command tree with all subcommands and options.
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

        // Print command name and description
        val description = spec.usageMessage().description().firstOrNull() ?: ""
        println("$indent${spec.name()} - $description")

        // Print options (skip standard help options at root level for brevity)
        val options =
            spec.options().filter { opt ->
                !opt.hidden() && !opt.usageHelp() && !opt.versionHelp()
            }

        for (opt in options) {
            val optDesc = opt.description().firstOrNull() ?: ""
            val names = opt.names().joinToString(", ")
            val paramLabel =
                if (opt.paramLabel().isNotEmpty() && opt.paramLabel() != "PARAM") {
                    " <${opt.paramLabel()}>"
                } else {
                    ""
                }
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

        // Recursively print subcommands
        val subcommands =
            cmd.subcommands.entries
                .filter {
                    !it.value.commandSpec
                        .usageMessage()
                        .hidden()
                }.sortedBy { it.key }

        for ((_, subCmd) in subcommands) {
            printCommandTree(subCmd, depth + 1)
        }
    }
}
