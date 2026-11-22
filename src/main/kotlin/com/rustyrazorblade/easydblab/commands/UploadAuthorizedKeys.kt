package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Parameters
import java.io.File
import java.io.FileFilter
import java.nio.file.Files

/**
 * Uploads the authorized (public) keys in a local directory to every node, so other operators can
 * SSH in. With no directory given it reads the workspace's `authorized_keys` directory.
 */
@RequireProfileSetup
@Command(
    name = "upload-keys",
    description = ["Upload authorized (public) keys from the workspace's authorized_keys directory"],
)
class UploadAuthorizedKeys : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Parameters(
        arity = "0..1",
        description = ["Local directory of authorized keys (default: the workspace's ${Constants.Workspace.AUTHORIZED_KEYS_DIR})"],
    )
    var localDir: String? = null

    @Mixin
    var hosts = HostsMixin()

    val authorizedKeysExtra = "~/.ssh/authorized_keys_extra"

    override fun execute() {
        // A typed directory resolves where the user typed it; the default is the workspace's.
        val keysDir = localDir?.let(::File) ?: context.workspaceFile(Constants.Workspace.AUTHORIZED_KEYS_DIR)
        if (!keysDir.exists()) {
            eventBus.emit(Event.Command.UploadKeysNotFound(keysDir.absolutePath))
            throw CommandFailedException("No authorized keys directory at ${keysDir.absolutePath}")
        }

        val files =
            keysDir
                .listFiles(FileFilter { it.name.endsWith(".pub") })
                ?.sortedBy { it.name } // listFiles order is filesystem-dependent
                ?: error("Failed to list files in $keysDir")
        // With no key, the append below would add a blank line to every node's authorized_keys.
        if (files.isEmpty()) {
            eventBus.emit(Event.Command.UploadKeysEmpty(keysDir.absolutePath))
            throw CommandFailedException("No .pub files in ${keysDir.absolutePath}")
        }
        println("Files: ${files.map { it.name }}")

        // collect all the keys into a single file then upload
        val keys = files.joinToString("\n") { it.readText().trim() }

        val authorizedKeysExtraFile = Files.createTempFile("authorized_keys_extra", null).toFile()
        try {
            authorizedKeysExtraFile.writeText(keys + "\n")

            println("Uploading the following keys:")
            println(keys)

            val upload = doUpload(authorizedKeysExtraFile)
            // One parallel fan-out over every node, so the scratch file outlives every upload.
            val targets =
                hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) +
                    hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Stress) +
                    hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Control)
            hostOperationsService.withHosts(targets, parallel = true) { upload(it.toHost()) }
        } finally {
            authorizedKeysExtraFile.delete()
        }
    }

    private fun doUpload(authorizedKeysFile: File) =
        { host: Host ->
            // Upload the file using RemoteOperationsService
            remoteOps.upload(host, authorizedKeysFile.toPath(), authorizedKeysExtra)

            // Append to authorized_keys
            remoteOps
                .executeRemotely(
                    host,
                    "cat $authorizedKeysExtra >> /home/ubuntu/.ssh/authorized_keys",
                ).text
        }
}
