package com.rustyrazorblade.easydblab.commands.kit.source

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for managing additional kit source directories.
 *
 * Subcommands let users register external parent directories as additional kit sources so
 * their kits appear in `kit list`, `kit info`, and `kit install` alongside built-in kits.
 * Registrations are persisted in `kit-sources.yaml` in the active profile directory.
 */
@Command(
    name = "source",
    description = ["Manage additional kit source directories"],
    mixinStandardHelpOptions = true,
    subcommands = [
        KitSourceAdd::class,
        KitSourceList::class,
        KitSourceRemove::class,
    ],
)
class KitSource : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(spec.commandLine().out)
    }
}
