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
        val vpcIds = vpcService.findVpcsByTag(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)

        if (vpcIds.isEmpty()) {
            eventBus.emit(Event.Command.NoVpcsFound)
            return
        }

        for (vpcId in vpcIds) {
            val tags = vpcService.getVpcTags(vpcId)
            val name = tags["Name"] ?: "(unnamed)"
            val clusterId = tags["ClusterId"] ?: "(no cluster id)"
            eventBus.emit(Event.Command.VpcListItem(name, vpcId, clusterId))
        }
    }
}
