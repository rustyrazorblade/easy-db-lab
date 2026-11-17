package com.rustyrazorblade.easydblab.services.bringup.aws

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.services.aws.CapacityPlan
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.LaunchCapacityPreflight
import com.rustyrazorblade.easydblab.services.bringup.BringUpPortSet
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.InstanceTracker
import com.rustyrazorblade.easydblab.services.bringup.OperatorRoute
import com.rustyrazorblade.easydblab.services.bringup.k3s.K3sBringUpSteps
import java.io.File

/**
 * What one EC2 bring-up is bound to: `up`'s state [state] writer, the operator's [route], the
 * [workspace], each server type's launch request (a replacement is launched with the same one),
 * and the capacity [plan] a replacement is checked against.
 */
data class Ec2BringUpRequest(
    val state: ClusterStateWriter,
    val route: OperatorRoute,
    val workspace: File,
    val launches: Map<ServerType, InstanceCreationConfig>,
    val plan: CapacityPlan,
)

/** Builds the ports of one bring-up on EC2; `up` holds it, so its tests can bring up over fakes. */
fun interface Ec2BringUpPorts {
    /** The ports for one bring-up bound to [request]. */
    fun ports(request: Ec2BringUpRequest): BringUpPortSet
}

/**
 * The ports of a bring-up on EC2 with K3s (design D12): a new [InstanceTracker] from [tracker] for
 * each run, [Ec2InstanceFleet] for replacements, and the K3s steps. It is the one place the
 * provider and the cluster software meet; a second provider builds its own.
 */
class Ec2K3sBringUp(
    private val ec2: EC2InstanceService,
    private val preflight: LaunchCapacityPreflight,
    private val tracker: () -> InstanceTracker,
    private val steps: K3sBringUpSteps,
) : Ec2BringUpPorts {
    override fun ports(request: Ec2BringUpRequest): BringUpPortSet =
        BringUpPortSet(
            tracker = tracker(),
            fleet = Ec2InstanceFleet(ec2, preflight, request.launches, request.plan),
            nodeSteps = steps.nodeSteps(request.state, request.workspace),
            controlSteps = steps.controlSteps(request.route, request.state, request.workspace),
            state = request.state,
        )
}
