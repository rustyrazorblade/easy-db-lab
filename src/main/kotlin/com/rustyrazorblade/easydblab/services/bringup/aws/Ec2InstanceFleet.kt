package com.rustyrazorblade.easydblab.services.bringup.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.services.aws.CapacityPlan
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.LaunchCapacityPreflight
import com.rustyrazorblade.easydblab.services.bringup.InstanceFleet
import java.time.Duration

/**
 * [InstanceFleet] on EC2: a replacement launches with the same request as the first launch of its
 * server type ([launches], the AMI, type, tags, subnets and EBS config `up` used), and its capacity
 * check uses `up`'s [plan] for the region, zones, CNI and VPC.
 */
class Ec2InstanceFleet(
    private val ec2: EC2InstanceService,
    private val preflight: LaunchCapacityPreflight,
    private val launches: Map<ServerType, InstanceCreationConfig>,
    private val plan: CapacityPlan,
    private val terminateTimeout: Duration = Duration.ofSeconds(Constants.Scale.REPLACEMENT_TERMINATE_TIMEOUT_SECONDS),
) : InstanceFleet {
    override fun terminate(instanceId: String) = ec2.terminateAndWait(instanceId, terminateTimeout)

    override fun verifyReplacement(
        serverType: ServerType,
        ordinal: Int,
    ) = preflight.verifyReplacement(plan, launchOf(serverType), ordinal)

    override fun launch(
        serverType: ServerType,
        ordinal: Int,
    ): ClusterHost = ec2.launchOne(launchOf(serverType), ordinal).toClusterHost()

    private fun launchOf(serverType: ServerType): InstanceCreationConfig =
        requireNotNull(launches[serverType]) { "No launch configuration for ${serverType.serverType} nodes" }
}
