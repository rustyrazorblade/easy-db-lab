package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.converters.PicoServerTypeConverter
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Deletes kit data directories directly from cluster node filesystems via SSH.
 *
 * Kit data lives on Local Persistent Volumes (LPVs) mounted at
 * [Constants.K8s.DB_MOUNT_PATH]/<kit> on each node. This command is an escape
 * hatch for wiping that data outside the normal kit uninstall lifecycle — for
 * example, when a kit's uninstall phase cannot run cleanly, or when you want to
 * reset data between test runs without fully reinstalling the cluster.
 *
 * For kits that declare a `platform-pvs-delete` uninstall step, the normal
 * `easy-db-lab <kit> uninstall` command handles this automatically.
 */
@RequireProfileSetup
@Command(
    name = "cleanup",
    description = ["Delete kit data directories from cluster nodes via SSH"],
    mixinStandardHelpOptions = true,
)
class Cleanup : PicoBaseCommand() {
    @Option(
        names = ["--kit"],
        description = ["Kit name (e.g. clickhouse, presto)"],
        required = true,
    )
    lateinit var kit: String

    @Option(
        names = ["--node-type"],
        description = ["Node pool to clean: cassandra or stress (default: cassandra)"],
        converter = [PicoServerTypeConverter::class],
    )
    var nodeType: ServerType = ServerType.Cassandra

    override fun execute() {
        require(kit.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$"))) {
            "Kit name must contain only letters, digits, hyphens, or underscores: $kit"
        }

        val dataPath = "${Constants.K8s.DB_MOUNT_PATH}/$kit"

        for (node in clusterState.getHosts(nodeType)) {
            remoteOps.executeRemotely(host = node, command = "sudo rm -rf $dataPath")
            eventBus.emit(Event.Cleanup.NodeCleaned(kit = kit, node = node.alias))
        }

        eventBus.emit(Event.Cleanup.Complete(kit = kit))
    }
}
