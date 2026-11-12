package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig

/**
 * The facts about one instance type that the pre-launch capacity checks need.
 *
 * @property vcpus the type's default vCPU count, which is what the vCPU quotas count
 * @property maxNetworkInterfaces the most ENIs an instance of the type can have attached
 * @property ipv4AddressesPerInterface the most private IPv4 addresses one of its ENIs holds
 */
data class InstanceTypeFacts(
    val vcpus: Int,
    val maxNetworkInterfaces: Int,
    val ipv4AddressesPerInterface: Int,
)

/** What a `RunInstances` dry run said about a launch. */
sealed interface LaunchDryRun {
    /** AWS answered `DryRunOperation`: the real call would have been allowed. */
    data object Permitted : LaunchDryRun

    /** AWS refused the launch; [errorCode] and [message] are AWS's own. */
    data class Refused(
        val errorCode: String,
        val message: String,
    ) : LaunchDryRun
}

/**
 * The account and region facts `up` checks before it launches anything: quotas, what is already
 * in use, where an instance type is offered, the free addresses of the cluster's subnets, and
 * whether AWS would accept the launch request at all.
 *
 * It exists so the decisions in [LaunchCapacityPreflight] are made on plain numbers, and the AWS
 * calls behind them live in one adapter ([Ec2AccountCapacity]).
 */
interface AccountCapacity {
    /** The facts for each of [instanceTypes]; a type AWS does not know is missing from the map. */
    fun instanceTypes(instanceTypes: Collection<String>): Map<String, InstanceTypeFacts>

    /** The region's quota of vCPUs for running On-Demand standard-family instances ([InstanceFamily]). */
    fun standardVcpuQuota(): Int

    /** The vCPUs of the running and pending On-Demand standard-family instances in the region. */
    fun standardVcpusInUse(): Int

    /** The zones among [zones] (full names, e.g. `us-west-2a`) where [instanceType] is offered. */
    fun zonesOffering(
        instanceType: String,
        zones: Collection<String>,
    ): Set<String>

    /** The region's quota of network interfaces. */
    fun networkInterfaceQuota(): Int

    /** The network interfaces that exist in the region now. */
    fun networkInterfacesInUse(): Int

    /** The free IPv4 addresses of [vpcId]'s subnets, summed per availability zone. */
    fun freeAddressesByZone(vpcId: String): Map<String, Int>

    /** Asks AWS, with `DryRun`, whether it would run the first RunInstances call of [config]'s launch. */
    fun dryRunLaunch(config: InstanceCreationConfig): LaunchDryRun
}

/**
 * Which EC2 vCPU quota an instance type counts against.
 *
 * The On-Demand standard quota covers the A, C, D, H, I, M, R, T and Z families, `im` and `is`
 * included (I-family storage types). Every other family (F, G, Inf, P, Trn, X, DL, HPC, Mac, U,
 * VT) has a quota of its own.
 */
object InstanceFamily {
    private val STANDARD = setOf("a", "c", "d", "h", "i", "m", "r", "t", "z", "im", "is")

    /** The family prefix of [instanceType]: the letters before its generation digit (`i4i.xlarge` → `i`). */
    fun of(instanceType: String): String = instanceType.lowercase().takeWhile { it.isLetter() }

    /** Whether [instanceType] counts against the On-Demand standard vCPU quota. */
    fun isStandard(instanceType: String): Boolean = of(instanceType) in STANDARD
}
