package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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

    private val eventBus: EventBus by inject()

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
        if (depth == 0) {
            eventBus.emit(Event.Message("${spec.name()} - $description"))
        } else {
            eventBus.emit(Event.Message("$indent${spec.name()} - $description"))
        }

        // Print options (skip standard help options at root level for brevity)
        val options =
            spec.options().filter { opt ->
                !opt.hidden() && opt.longestName() !in listOf("--help", "--version", "-h", "-V")
            }

        for (opt in options) {
            val optDesc = opt.description().firstOrNull() ?: ""
            val names = opt.names().joinToString(", ")
            val required = if (opt.required()) " (required)" else ""
            val paramLabel =
                if (opt.paramLabel().isNotEmpty() && opt.paramLabel() != "PARAM") {
                    " <${opt.paramLabel()}>"
                } else {
                    ""
                }
            eventBus.emit(Event.Message("$indent    $names$paramLabel$required - $optDesc"))
        }

        // Print positional parameters
        for (param in spec.positionalParameters()) {
            if (!param.hidden()) {
                val paramDesc = param.description().firstOrNull() ?: ""
                val required = if (param.required()) " (required)" else ""
                eventBus.emit(Event.Message("$indent    <${param.paramLabel()}>$required - $paramDesc"))
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
