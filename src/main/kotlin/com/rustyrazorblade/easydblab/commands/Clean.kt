package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import java.io.File

/**
 * Cleans up generated files from the current directory.
 *
 * It removes only what easy-db-lab wrote. In `bin/` that is the tool wrappers and their marker, by
 * name; any other file there stays, and so does `bin/` itself while it holds one.
 */
@McpCommand
@Command(
    name = "clean",
    description = ["Clean up generated files from the current directory"],
)
class Clean :
    PicoCommand,
    KoinComponent {
    private val context: Context by inject()
    private val eventBus: EventBus by inject()
    private val toolWrapperInstaller: ToolWrapperInstaller by inject()

    companion object {
        val filesToClean =
            listOf(
                "cassandra.patch.yaml",
                "sshConfig",
                "env.sh",
                "environment.sh",
                "setup_instance.sh",
                "state.json",
                "cassandra_versions.yaml",
                "axonops-workbench.json",
                ".socks5-proxy-state",
                Constants.Proxy.ENV_FILE,
                "kubeconfig",
            )

        val directoriesToClean =
            listOf(
                "provisioning",
                "cassandra",
                "k8s",
            )
    }

    override fun execute() {
        for (f in filesToClean) {
            File(context.workingDirectory, f).deleteRecursively()
        }

        for (d in directoriesToClean) {
            File(context.workingDirectory, d).deleteRecursively()
        }
        toolWrapperInstaller.remove(context.workingDirectory)
        val artifacts = File(context.workingDirectory, "artifacts")

        if (artifacts.isDirectory) {
            if (artifacts.listFiles().isEmpty()) {
                artifacts.delete()
            } else {
                eventBus.emit(Event.Command.ArtifactsNotEmpty)
            }
        }
    }
}
