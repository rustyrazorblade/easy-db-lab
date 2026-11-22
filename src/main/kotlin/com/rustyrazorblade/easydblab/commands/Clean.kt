package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.proxy.EnvScriptWriter
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Cleans up generated files from the workspace.
 *
 * It removes only what easy-db-lab wrote. In `bin/` that is the tool wrappers and their marker, by
 * name; any other file there stays, and so does `bin/` itself while it holds one.
 */
@Command(
    name = "clean",
    description = ["Clean up generated files from the workspace"],
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
                Constants.ConfigPaths.CASSANDRA_PATCH_FILE,
                Constants.Workspace.SSH_CONFIG_FILE,
                EnvScriptWriter.FILE_NAME,
                Constants.Workspace.STRESS_ENV_FILE,
                Constants.Workspace.SETUP_INSTANCE_SCRIPT,
                CLUSTER_STATE,
                Constants.ClusterStateFile.LOCK_FILE,
                Constants.Workspace.AXONOPS_CONFIG_FILE,
                Constants.Vpc.SOCKS5_PROXY_STATE_FILE,
                Constants.Proxy.ENV_FILE,
                Constants.Proxy.ENV_LOCK_FILE,
                Constants.K3s.LOCAL_KUBECONFIG,
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
            context.workspaceFile(f).deleteRecursively()
        }

        for (d in directoriesToClean) {
            context.workspaceFile(d).deleteRecursively()
        }
        toolWrapperInstaller.remove(context.workingDirectory)
        val artifacts = context.workspaceFile("artifacts")

        if (artifacts.isDirectory) {
            if (artifacts.listFiles().isEmpty()) {
                artifacts.delete()
            } else {
                eventBus.emit(Event.Command.ArtifactsNotEmpty)
            }
        }
    }
}
