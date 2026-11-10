package com.rustyrazorblade.easydblab

import picocli.CommandLine.Command

/**
 * The names of the top-level commands compiled into the CLI, read from [EasyDBLabCommand]'s
 * `@Command` annotation. A kit registers under its own name at the top level, so a kit named
 * like one of these would be unreachable; both startup registration and `kit install` use this
 * set to refuse that. It is read from the annotation, not the live command tree, so a kit that
 * was already registered never counts as built in.
 */
object BuiltInCommands {
    val names: Set<String> by lazy {
        EasyDBLabCommand::class.java
            .getAnnotation(Command::class.java)
            .subcommands
            .mapNotNull { it.java.getAnnotation(Command::class.java)?.name }
            .toSet()
    }

    fun isBuiltIn(name: String): Boolean = name in names
}
