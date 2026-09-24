package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.install.KitRunnerCommand
import com.rustyrazorblade.easydblab.services.CommandExecutor
import org.koin.core.component.inject
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

    private val commandExecutor: CommandExecutor by inject()

    /** The uninstall phase's exit code, so a failed uninstall fails `kit uninstall`. */
    private var exitCode = 0

    override fun call(): Int {
        val lifecycleExit = super.call()
        return if (lifecycleExit != 0) lifecycleExit else exitCode
    }

    override fun execute() {
        val kitDir = File(context.workingDirectory, kit)
        if (!kitDir.isDirectory) {
            error("Kit '$kit' is not installed in ${context.workingDirectory}")
        }
        // Through the command executor, so the phase gets the SOCKS tunnel its @RequiresProxy
        // declares (its steps and the metrics deregistration reach the Kubernetes API).
        exitCode = commandExecutor.execute { KitRunnerCommand(kit, kitDir, Constants.Kit.PHASE_UNINSTALL) }
    }
}
