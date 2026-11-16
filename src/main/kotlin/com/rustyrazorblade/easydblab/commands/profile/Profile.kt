package com.rustyrazorblade.easydblab.commands.profile

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Top-level parent command for profile inspection and setup.
 *
 * Subcommands:
 * - `profile show`  — report the active profile's name, directory, and settings
 * - `profile setup` — run the interactive profile setup workflow
 *
 * Deliberately a [Runnable] rather than a
 * [com.rustyrazorblade.easydblab.kernel.PicoCommand]: the execution strategy in
 * [com.rustyrazorblade.easydblab.CommandLineParser] routes only `PicoCommand`s through
 * `CommandExecutor`, so a group that is merely `Runnable` falls through to picocli's `RunLast`,
 * which prints usage and exits 0 when no subcommand is given.
 */
@Command(
    name = "profile",
    description = ["Profile management: show the active profile or set one up"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProfileShow::class,
        SetupProfile::class,
    ],
)
class Profile : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(spec.commandLine().out)
    }
}
