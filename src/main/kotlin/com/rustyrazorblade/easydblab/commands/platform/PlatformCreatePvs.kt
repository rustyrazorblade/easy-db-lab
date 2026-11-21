package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.KitStorageProvisioner
import com.rustyrazorblade.easydblab.services.KitVolumes
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@RequireProfileSetup
@RequiresProxy
@Command(
    name = "create-pvs",
    description = ["Create a kit's local StorageClass and PersistentVolumes on cluster nodes"],
)
class PlatformCreatePvs : PicoBaseCommand() {
    private val kitStorageProvisioner: KitStorageProvisioner by inject()

    @Option(
        names = ["--kit"],
        description = ["Kit name (used as PV name prefix and disk path component)"],
        required = true,
    )
    lateinit var kit: String

    @Option(
        names = ["--size"],
        description = ["Storage size per PV (e.g. 100Gi, 500Gi)"],
        required = true,
    )
    lateinit var size: String

    @Option(
        names = ["--node-type"],
        description = ["Node pool to create PVs on: db or app (default: db)"],
    )
    var nodeType: String = "db"

    @Option(
        names = ["--pvc-name"],
        description = ["PVC template name to bind (default: data)"],
    )
    var pvcTemplateName: String = "data"

    override fun execute() {
        require(Constants.Kit.NAME_PATTERN.matches(kit)) {
            "Invalid --kit '$kit': use letters, digits, hyphens or underscores, and start with a letter or digit"
        }
        val serverType = ServerType.from(nodeType)
        checkNotNull(clusterState.getControlHost()) { "No control node found. Is the cluster running?" }
        val targetHosts = clusterState.hosts[serverType].orEmpty()
        check(targetHosts.isNotEmpty()) { "No $nodeType nodes found in cluster state." }
        val storage = KitStorage.from(clusterState)
        val volumes = KitVolumes(kit = kit, nodeType = nodeType, volumeClaimTemplateName = pvcTemplateName, size = size)

        when (storage) {
            is KitStorage.LocalVolumes -> {
                eventBus.emit(
                    Event.Platform.CreatingPvs(kit = kit, nodeType = nodeType, count = targetHosts.size, size = size),
                )
                runCatching { kitStorageProvisioner.provision(volumes, storage, clusterState) }
                    .getOrElse { exception -> throw IllegalStateException("Failed to create PVs for $kit: ${exception.message}", exception) }
                eventBus.emit(Event.Platform.PvsCreated(kit = kit, count = targetHosts.size))
            }
            // The cluster provisions the volumes; the provisioner only refuses a class that would
            // delete them, and its refusal already names the class and the fix.
            is KitStorage.Provisioned -> {
                kitStorageProvisioner.provision(volumes, storage, clusterState)
                eventBus.emit(Event.Platform.KitStorageClassChecked(kit = kit, storageClass = storage.storageClass))
            }
        }
    }
}
