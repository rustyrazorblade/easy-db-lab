package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import java.util.Collections

/**
 * An [AccountCapacity] whose answers are plain fields, for tests of the decisions made on them.
 * Every call is recorded in [calls], so a test can prove that no AWS call was made at all.
 *
 * The defaults describe an account with room for any test cluster: every type is offered in
 * every zone, and a zone missing from [freeAddresses] has no subnet yet.
 */
class FakeAccountCapacity(
    var types: Map<String, InstanceTypeFacts> =
        mapOf(
            "i4i.xlarge" to InstanceTypeFacts(vcpus = 4, maxNetworkInterfaces = 4, ipv4AddressesPerInterface = 15),
            "c6id.2xlarge" to InstanceTypeFacts(vcpus = 8, maxNetworkInterfaces = 4, ipv4AddressesPerInterface = 15),
            "m5d.xlarge" to InstanceTypeFacts(vcpus = 4, maxNetworkInterfaces = 4, ipv4AddressesPerInterface = 15),
        ),
    var standardQuota: Int = 1_000_000,
    var standardInUse: Int = 0,
    var offeredZones: Map<String, Set<String>> = emptyMap(),
    var eniQuota: Int = 1_000_000,
    var enisInUse: Int = 0,
    var freeAddresses: Map<String, Int> = emptyMap(),
    var dryRun: (InstanceCreationConfig) -> LaunchDryRun = { LaunchDryRun.Permitted },
) : AccountCapacity {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val dryRuns: MutableList<InstanceCreationConfig> = Collections.synchronizedList(mutableListOf())

    override fun instanceTypes(instanceTypes: Collection<String>): Map<String, InstanceTypeFacts> {
        calls += "instanceTypes"
        return types.filterKeys { it in instanceTypes }
    }

    override fun standardVcpuQuota(): Int {
        calls += "standardVcpuQuota"
        return standardQuota
    }

    override fun standardVcpusInUse(): Int {
        calls += "standardVcpusInUse"
        return standardInUse
    }

    override fun zonesOffering(
        instanceType: String,
        zones: Collection<String>,
    ): Set<String> {
        calls += "zonesOffering"
        return offeredZones[instanceType]?.intersect(zones.toSet()) ?: zones.toSet()
    }

    override fun networkInterfaceQuota(): Int {
        calls += "networkInterfaceQuota"
        return eniQuota
    }

    override fun networkInterfacesInUse(): Int {
        calls += "networkInterfacesInUse"
        return enisInUse
    }

    override fun freeAddressesByZone(vpcId: String): Map<String, Int> {
        calls += "freeAddressesByZone"
        return freeAddresses
    }

    override fun dryRunLaunch(config: InstanceCreationConfig): LaunchDryRun {
        calls += "dryRunLaunch"
        dryRuns += config
        return dryRun(config)
    }
}
