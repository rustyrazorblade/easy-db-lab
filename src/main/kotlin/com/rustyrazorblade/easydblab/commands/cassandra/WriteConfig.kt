package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraConfigPatch
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraPlacement
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Write a new cassandra configuration patch file: the keys both Cassandra paths share
 * ([CassandraConfigPatch.common]) plus the VM placement (the first db node as the seed, `/mnt/db1`,
 * `Ec2Snitch`). The cassandra-pods kit merges its own placement after this file, so the VM keys
 * here never reach a pod.
 */
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "write-config",
    aliases = ["wc"],
    description = ["Write a new cassandra configuration patch file"],
)
class WriteConfig : PicoBaseCommand() {
    @Parameters(description = ["Patch file name"], defaultValue = "cassandra.patch.yaml")
    var file: String = "cassandra.patch.yaml"

    @Option(names = ["-t", "--tokens"], description = ["Number of tokens"])
    var tokens: Int = Constants.CassandraConfig.DEFAULT_TOKENS

    override fun execute() {
        eventBus.emit(Event.Cassandra.WriteConfigDone(file))
        eventBus.emit(Event.Cassandra.WriteConfigHint)

        val seeds = clusterState.getHosts(ServerType.Cassandra).map { it.private }.take(1)
        val patch =
            CassandraConfigPatch.common(clusterState.name, tokens) +
                CassandraConfigPatch.placement(CassandraPlacement.Vm(seedIps = seeds))

        File(context.workingDirectory, file).writeText(patch.toYaml() + "\n")
    }
}
