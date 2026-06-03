package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommand
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.io.File

/**
 * Removes an installed kit and its local files.
 *
 * Runs the kit's `uninstall` lifecycle phase (if declared) before deleting the kit directory.
 */
@Command(
    name = "uninstall",
    description = ["Remove an installed kit and its local files"],
    mixinStandardHelpOptions = true,
)
class Uninstall : PicoBaseCommand() {
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--kit"],
        description = ["Kit to uninstall"],
    )
    var kit: String? = null

    override fun execute() {
        val target =
            kit ?: run {
                spec.commandLine().usage(System.out)
                return
            }
        val kitDir = File(context.workingDirectory, target)
        if (!kitDir.isDirectory) {
            error("Kit '$target' is not installed in ${context.workingDirectory}")
        }
        KitRunnerCommand(target, kitDir, "uninstall").call()
    }
}
