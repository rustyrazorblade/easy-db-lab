package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.PersistentVolumeConfig
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@RequireProfileSetup
@Command(
    name = "create-pvs",
    description = ["Create per-workload local PersistentVolumes on cluster nodes"],
)
class PlatformCreatePvs : PicoBaseCommand() {
    private val k8sService: K8sService by inject()

    @Option(
        names = ["--workload"],
        description = ["Workload name (used as PV name prefix and disk path component)"],
        required = true,
    )
    lateinit var workload: String

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

    override fun execute() {
        require(nodeType == "db" || nodeType == "app") {
            "node-type must be 'db' or 'app', got: $nodeType"
        }

        val serverType = if (nodeType == "app") ServerType.Stress else ServerType.Cassandra
        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found. Is the cluster running?")
        val targetHosts =
            clusterState.hosts[serverType]
                ?: error("No $nodeType nodes found in cluster state.")

        check(targetHosts.isNotEmpty()) { "No $nodeType nodes found in cluster state." }

        eventBus.emit(Event.Platform.CreatingPvs(workload = workload, nodeType = nodeType, count = targetHosts.size, size = size))

        k8sService
            .createLocalPersistentVolumes(
                controlHost = controlHost,
                config =
                    PersistentVolumeConfig(
                        dbName = workload,
                        localPath = "${Constants.K8s.DB_MOUNT_PATH}/$workload",
                        count = targetHosts.size,
                        storageSize = size,
                        storageClass = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                        namespace = Constants.K8s.NAMESPACE,
                        volumeClaimTemplateName = "data",
                    ),
            ).getOrElse { exception ->
                error("Failed to create PVs for $workload: ${exception.message}")
            }

        eventBus.emit(Event.Platform.PvsCreated(workload = workload, count = targetHosts.size))
    }
}
