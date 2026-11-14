package com.rustyrazorblade.easydblab.commands

import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec

/**
 * Adds picocli's standard help options to a command without taking over an option the command owns.
 *
 * `mixinStandardHelpOptions` brings `--help`/`-h` and `--version`/`-V` together.  A command that
 * declares its own `--version` (a database version to download, upload or start) collides with the
 * mixin's `--version`, so `--help` fails with "Unknown option".  For such a command only
 * `--help`/`-h` is added and `--version` stays the command's own.
 */
object StandardHelpOptions {
    private const val VERSION_OPTION = "--version"
    private const val HELP_OPTION = "--help"

    fun apply(spec: CommandSpec) {
        if (spec.findOption(VERSION_OPTION) == null) {
            spec.mixinStandardHelpOptions(true)
        } else if (spec.findOption(HELP_OPTION) == null) {
            spec.addOption(
                OptionSpec
                    .builder("-h", HELP_OPTION)
                    .usageHelp(true)
                    .type(Boolean::class.java)
                    .description("Show this help message and exit.")
                    .build(),
            )
        }
    }
}
