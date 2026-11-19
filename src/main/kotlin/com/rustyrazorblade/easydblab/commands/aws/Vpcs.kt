package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Lists all easy-db-lab VPCs in the current AWS region.
 *
 * Displays one VPC per line with: name, vpc-id, cluster-id
 */
@Command(
    name = "vpcs",
    description = ["List all easy-db-lab VPCs"],
    mixinStandardHelpOptions = true,
)
class Vpcs : PicoBaseCommand() {
    private val vpcService: VpcService by inject()

    override fun execute() {
        val vpcIds = vpcService.findVpcsByTag(Constants.Vpc.LEGACY_TAG_KEY, Constants.Vpc.TAG_VALUE)

        if (vpcIds.isEmpty()) {
            eventBus.emit(Event.Command.NoVpcsFound)
            return
        }

        for (vpcId in vpcIds) {
            val tags = vpcService.getVpcTags(vpcId)
            val name = tags[Constants.Vpc.NAME_TAG_KEY] ?: "(unnamed)"
            val clusterId = tags[Constants.Vpc.CLUSTER_ID_TAG_KEY] ?: "(no cluster id)"
            println("$name $vpcId $clusterId")
        }
    }
}
