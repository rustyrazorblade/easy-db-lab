package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig

/**
 * The instances of one server type that `up` is about to launch: one per ordinal in [indexes],
 * the same ordinals the launch itself uses. Instances that already exist are not in it.
 */
data class LaunchGroup(
    val serverType: ServerType,
    val instanceType: String,
    val indexes: List<Int>,
) {
    /** How many instances of the group are launched. */
    val newCount: Int get() = indexes.size
}

/**
 * Everything the capacity checks need to know about a launch.
 *
 * @property zones the zone letters, in the order the VPC's subnets are made (index `i` lands in
 *   zone `i % zones.size`)
 * @property existingVpcId the VPC if it exists already; without one the subnets are sized from [vpcCidr]
 */
data class CapacityPlan(
    val groups: List<LaunchGroup>,
    val region: String,
    val zones: List<String>,
    val cni: CniMode,
    val existingVpcId: String?,
    val vpcCidr: CidrBlock,
)

/**
 * The checks `up` makes before its first RunInstances, so a cluster the account cannot hold fails
 * at once, naming what is short and by how much, instead of part-way through 1,000 launches.
 *
 * Every check counts only the instances that will be launched ([LaunchGroup.newCount]), so a
 * re-run with a larger count checks the difference. With nothing to launch no AWS call is made.
 * Every shortfall is reported, one event each, before `up` stops.
 *
 * - The zone count fits the VPC's [Constants.Vpc.MAX_AVAILABILITY_ZONES] subnets.
 * - The instance type is offered in every zone a new instance lands in.
 * - The On-Demand standard vCPU quota has room for the new instances of the standard families;
 *   a type of another family is reported as not checked ([Event.Provision.VcpuQuotaNotChecked]).
 * - The network interface quota has room for each new node's ENIs: one under Flannel; under
 *   Cilium, as many as its node address and [Constants.Capacity.POD_ADDRESSES_PER_NODE] pod
 *   addresses fill, at most the type's maximum.
 * - Each zone's subnet has free addresses for the node address and, under Cilium, the pod
 *   addresses of every new node that lands in it.
 *
 * [verifyLaunchPermitted] runs once the subnets and the security group exist: a RunInstances dry
 * run per server type, with the request the real launch makes.
 */
class LaunchCapacityPreflight(
    private val capacity: AccountCapacity,
    private val eventBus: EventBus,
) {
    /** Fails, after reporting every shortfall, when the account cannot hold [plan]'s new instances. */
    fun verifyCapacity(plan: CapacityPlan) {
        val groups = plan.groups.filter { it.newCount > 0 }
        if (groups.isEmpty()) return

        verifyZoneCount(plan)
        val facts = capacity.instanceTypes(groups.map { it.instanceType }.toSet())
        groups.map { it.instanceType }.firstOrNull { it !in facts }?.let { unknown ->
            error("Instance type '$unknown' is not known in region ${plan.region}.")
        }

        val shortfalls =
            listOfNotNull(vcpuShortfall(groups, facts), eniShortfall(groups, facts, plan.cni)) +
                offeringShortfalls(groups, plan) +
                addressShortfalls(groups, plan)
        shortfalls.forEach(eventBus::emit)
        check(shortfalls.isEmpty()) {
            "The account cannot hold the new instances:\n" + shortfalls.joinToString("\n") { "- ${it.toDisplayString()}" }
        }
        eventBus.emit(Event.Provision.LaunchCapacityVerified(groups.sumOf { it.newCount }))
    }

    /** Fails with AWS's own message when a RunInstances dry run of any of [launches] is refused. */
    fun verifyLaunchPermitted(launches: List<InstanceCreationConfig>) {
        val refusals =
            launches.filter { it.count > 0 }.mapNotNull { launch ->
                when (val answer = capacity.dryRunLaunch(launch)) {
                    LaunchDryRun.Permitted -> null
                    is LaunchDryRun.Refused ->
                        Event.Provision.LaunchDryRunRefused(
                            launch.serverType.serverType,
                            launch.instanceType,
                            answer.errorCode,
                            answer.message,
                        )
                }
            }
        refusals.forEach(eventBus::emit)
        check(refusals.isEmpty()) {
            "AWS would refuse the launch:\n" + refusals.joinToString("\n") { "- ${it.toDisplayString()}" }
        }
    }

    private fun verifyZoneCount(plan: CapacityPlan) {
        val max = Constants.Vpc.MAX_AVAILABILITY_ZONES
        if (plan.zones.size <= max) return
        val event = Event.Provision.TooManyAvailabilityZones(plan.zones.size, max)
        eventBus.emit(event)
        error(
            "The cluster is configured for ${plan.zones.size} availability zones, " +
                "but the VPC is split into at most $max subnets.",
        )
    }

    private fun vcpuShortfall(
        groups: List<LaunchGroup>,
        facts: Map<String, InstanceTypeFacts>,
    ): Event.Provision.VcpuQuotaInsufficient? {
        val (standard, other) = groups.partition { InstanceFamily.isStandard(it.instanceType) }
        other.map { it.instanceType }.distinct().forEach { type ->
            eventBus.emit(Event.Provision.VcpuQuotaNotChecked(type, InstanceFamily.of(type)))
        }
        if (standard.isEmpty()) return null

        val needed = standard.sumOf { it.newCount * facts.getValue(it.instanceType).vcpus }
        val quota = capacity.standardVcpuQuota()
        val inUse = capacity.standardVcpusInUse()
        val shortBy = needed - (quota - inUse)
        return if (shortBy > 0) Event.Provision.VcpuQuotaInsufficient(needed, quota, inUse, shortBy) else null
    }

    private fun eniShortfall(
        groups: List<LaunchGroup>,
        facts: Map<String, InstanceTypeFacts>,
        cni: CniMode,
    ): Event.Provision.NetworkInterfaceQuotaInsufficient? {
        val needed = groups.sumOf { it.newCount * enisPerNode(facts.getValue(it.instanceType), cni) }
        val quota = capacity.networkInterfaceQuota()
        val inUse = capacity.networkInterfacesInUse()
        val shortBy = needed - (quota - inUse)
        return if (shortBy > 0) Event.Provision.NetworkInterfaceQuotaInsufficient(needed, quota, inUse, shortBy) else null
    }

    private fun enisPerNode(
        type: InstanceTypeFacts,
        cni: CniMode,
    ): Int =
        when (cni) {
            CniMode.Flannel -> 1
            CniMode.Cilium -> {
                val perEni = type.ipv4AddressesPerInterface.coerceAtLeast(1)
                ceilDiv(addressesPerNode(cni), perEni).coerceAtMost(type.maxNetworkInterfaces).coerceAtLeast(1)
            }
        }

    private fun offeringShortfalls(
        groups: List<LaunchGroup>,
        plan: CapacityPlan,
    ): List<Event.Provision.InstanceTypeNotOffered> =
        groups.mapNotNull { group ->
            val landing = zoneIndexes(group, plan).map { fullZone(plan, it) }.toSet()
            val missing = (landing - capacity.zonesOffering(group.instanceType, landing)).sorted()
            if (missing.isEmpty()) null else Event.Provision.InstanceTypeNotOffered(group.instanceType, missing)
        }

    private fun addressShortfalls(
        groups: List<LaunchGroup>,
        plan: CapacityPlan,
    ): List<Event.Provision.SubnetAddressesInsufficient> {
        val nodesPerZone =
            groups
                .flatMap { zoneIndexes(it, plan) }
                .groupingBy { fullZone(plan, it) }
                .eachCount()
        val free = plan.existingVpcId?.let(capacity::freeAddressesByZone).orEmpty()
        val emptySubnet = (plan.vpcCidr.subnetAddressCount() - Constants.Capacity.AWS_RESERVED_SUBNET_ADDRESSES).toInt()
        return nodesPerZone.toSortedMap().mapNotNull { (zone, nodes) ->
            val needed = nodes * addressesPerNode(plan.cni)
            val available = free[zone] ?: emptySubnet
            val shortBy = needed - available
            if (shortBy > 0) Event.Provision.SubnetAddressesInsufficient(zone, needed, available, shortBy) else null
        }
    }

    /** The zone index of each new instance of [group]: instance `i` lands in subnet `i % zones`. */
    private fun zoneIndexes(
        group: LaunchGroup,
        plan: CapacityPlan,
    ): List<Int> = group.indexes.map { it % plan.zones.size }

    private fun fullZone(
        plan: CapacityPlan,
        index: Int,
    ): String = plan.region + plan.zones[index]

    /** A node's own address, and under Cilium the pod addresses its ENIs take from the subnet. */
    private fun addressesPerNode(cni: CniMode): Int =
        when (cni) {
            CniMode.Flannel -> 1
            CniMode.Cilium -> 1 + Constants.Capacity.POD_ADDRESSES_PER_NODE
        }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b
}
