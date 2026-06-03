package com.rustyrazorblade.easydblab.commands.kit

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Top-level parent command for all kit management operations.
 *
 * Subcommands:
 * - `kit install <kit>` — scaffold kit files from a built-in or custom template
 * - `kit info --name`   — show details about a kit before installing
 * - `kit list`          — list all discoverable kit templates
 * - `kit uninstall`     — remove an installed kit and its local files
 *
 * Dynamic per-kit subcommands are registered under `kit install` at startup by
 * [com.rustyrazorblade.easydblab.CommandLineParser.registerDynamicInstallSubcommands].
 */
@Command(
    name = "kit",
    description = ["Kit management: install, list, and uninstall database workload kits"],
    mixinStandardHelpOptions = true,
    subcommands = [
        Install::class,
        KitInfo::class,
        KitList::class,
        Uninstall::class,
    ],
)
class Kit : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
