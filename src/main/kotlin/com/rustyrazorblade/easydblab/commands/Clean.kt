package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import java.io.File

/**
 * Cleans up generated files from the current directory.
 */
@McpCommand
@Command(
    name = "clean",
    description = ["Clean up generated files from the current directory"],
)
class Clean(
    private val context: Context,
) : PicoCommand,
    KoinComponent {
    private val outputHandler: OutputHandler by inject()

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
        val artifacts = File(context.workingDirectory, "artifacts")

        if (artifacts.isDirectory) {
            if (artifacts.listFiles().isEmpty()) {
                artifacts.delete()
            } else {
                outputHandler.handleMessage(
                    "Not deleting artifacts directory, it contains artifacts.",
                )
            }
        }
    }
}
