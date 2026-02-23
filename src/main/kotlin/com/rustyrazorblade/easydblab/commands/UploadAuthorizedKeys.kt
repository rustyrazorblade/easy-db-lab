package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Parameters
import java.io.File
import java.io.FileFilter
import java.io.FileWriter
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Upload authorized (public) keys from the ./authorized_keys directory.
 */
@RequireProfileSetup
@Command(
    name = "upload-keys",
    description = ["Upload authorized (public) keys from the ./authorized_keys directory"],
)
class UploadAuthorizedKeys : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()

    @Parameters(description = ["Local directory of authorized keys"], defaultValue = "authorized_keys")
    var localDir = "authorized_keys"

    @Mixin
    var hosts = HostsMixin()

    val authorizedKeysExtra = "~/.ssh/authorized_keys_extra"

    override fun execute() {
        val path = Paths.get(localDir)
        if (!path.exists()) {
            eventBus.emit(Event.Message("$localDir does not exist"))
            exitProcess(1)
        }

        val files =
            File(localDir).listFiles(FileFilter { it.name.endsWith(".pub") })
                ?: error("Failed to list files in $localDir")
        eventBus.emit(Event.Message("Files: ${files.map { it.name }}"))

        // collect all the keys into a single file then upload
        val keys = files.joinToString("\n") { it.readText().trim() }

        val authorizedKeysExtraFile = File("authorized_keys_extra")
        FileWriter(authorizedKeysExtraFile).use {
            it.write(keys)
            it.write("\n")
        }

        eventBus.emit(Event.Message("Uploading the following keys:"))
        eventBus.emit(Event.Message(keys))

        val upload = doUpload(authorizedKeysExtraFile)
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { upload(it.toHost()) }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Stress, "") { upload(it.toHost()) }
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Control, "") { upload(it.toHost()) }
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
