package com.rustyrazorblade.easydblab.commands.cassandra

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.inputStream

/**
 * Upload the cassandra.yaml fragment to all nodes and apply to cassandra.yaml.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "update-config",
    aliases = ["uc"],
    description = ["Upload the cassandra.yaml fragment to all nodes and apply to cassandra.yaml"],
)
class UpdateConfig : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val commandExecutor: CommandExecutor by inject()

    @Mixin
    var hosts = HostsMixin()

    @Parameters(description = ["Patch file to upload"], defaultValue = "cassandra.patch.yaml")
    var file: String = "cassandra.patch.yaml"

    @Option(names = ["--version"], description = ["Version to upload, default is current"])
    var version = "current"

    @Option(names = ["--restart", "-r"], description = ["Restart cassandra after patching"])
    var restart = false

    override fun execute() {
        // upload the patch file
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            val it = host.toHost()
            eventBus.emit(Event.Message("Uploading $file to $it"))

            val yaml = context.yaml.readTree(Path.of(file).inputStream())
            (yaml as ObjectNode)
                .put("listen_address", it.private)
                .put("rpc_address", it.private)
                .put("broadcast_rpc_address", it.private)

            eventBus.emit(Event.Message("Patching $it"))
            val tmp = Files.createTempFile("easydblab", "yaml")
            context.yaml.writeValue(tmp.toFile(), yaml)

            // call the patch command on the server
            remoteOps.upload(it, tmp, file)
            tmp.deleteExisting()
            val resolvedVersion = remoteOps.getRemoteVersion(it, version)

            // Execute patch-config and handle errors
            val patchResult = remoteOps.executeRemotely(it, "/usr/local/bin/patch-config $file")
            if (patchResult.stderr.isNotEmpty()) {
                eventBus.emit(Event.Error("patch-config stderr: ${patchResult.stderr}"))
                error("Failed to patch configuration on ${it.alias}: ${patchResult.stderr}")
            }
            eventBus.emit(Event.Message(patchResult.text))

            // Create a temporary directory on the remote filesystem using mktemp
            val tempDir =
                remoteOps.executeRemotely(it, "mktemp -d -t easydblab.XXXXXX").text.trim()
            eventBus.emit(Event.Message("Created temporary directory $tempDir on $it"))

            // Upload files to the temporary directory first
            eventBus.emit(
                Event.Message("Uploading configuration files to temporary directory $tempDir"),
            )
            remoteOps.uploadDirectory(it, resolvedVersion.file, tempDir)

            // Make sure the destination directory exists
            remoteOps.executeRemotely(it, "sudo mkdir -p ${resolvedVersion.conf}").text

            // Copy files from temp directory to the final location
            eventBus.emit(
                Event.Message("Copying files from temporary directory to ${resolvedVersion.conf}"),
            )
            remoteOps.executeRemotely(it, "sudo cp -R $tempDir/* ${resolvedVersion.conf}/").text

            // Change ownership of all files
            remoteOps
                .executeRemotely(
                    it,
                    "sudo chown -R cassandra:cassandra ${resolvedVersion.conf}",
                ).text

            // Clean up the temporary directory
            remoteOps.executeRemotely(it, "rm -rf $tempDir").text

            eventBus.emit(Event.Message("Configuration updated for $it"))

            uploadSidecarConfig(it)
        }

        if (restart) {
            commandExecutor.execute {
                Restart().apply { this.hosts = this@UpdateConfig.hosts }
            }
        }
    }

    /**
     * Upload sidecar config with host's private IP.
     *
     * This method modifies the cassandra-sidecar.yaml to set the host-specific IP address
     * in both the cassandra_instances and driver_parameters sections.
     */
    private fun uploadSidecarConfig(host: Host) {
        val sidecarYaml =
            context.yaml.readTree(
                Path.of(Constants.ConfigPaths.CASSANDRA_SIDECAR_CONFIG).toFile().inputStream(),
            )
        val instances = sidecarYaml.get("cassandra_instances") as ArrayNode
        (instances[0] as ObjectNode).put("host", host.private)

        // Update driver_parameters.contact_points
        val driverParams = sidecarYaml.get("driver_parameters") as ObjectNode
        val contactPoints = driverParams.putArray("contact_points")
        contactPoints.add("${host.private}:9042")

        val sidecarTmp = Files.createTempFile("sidecar", ".yaml")
        context.yaml.writeValue(sidecarTmp.toFile(), sidecarYaml)
        remoteOps.upload(host, sidecarTmp, "cassandra-sidecar.yaml")
        sidecarTmp.deleteExisting()

        remoteOps.executeRemotely(
            host,
            "sudo mv cassandra-sidecar.yaml ${Constants.ConfigPaths.CASSANDRA_REMOTE_SIDECAR_CONFIG}",
        )
        eventBus.emit(Event.Message("Sidecar configuration updated for $host"))
    }
}
