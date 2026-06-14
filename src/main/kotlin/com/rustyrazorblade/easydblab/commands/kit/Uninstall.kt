package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommand
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
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
    @Parameters(
        index = "0",
        description = ["Name of the kit to uninstall (e.g. kafka, clickhouse)"],
    )
    lateinit var kit: String

    override fun execute() {
        val kitDir = File(context.workingDirectory, kit)
        if (!kitDir.isDirectory) {
            error("Kit '$kit' is not installed in ${context.workingDirectory}")
        }
        KitRunnerCommand(kit, kitDir, "uninstall").call()
    }
}
