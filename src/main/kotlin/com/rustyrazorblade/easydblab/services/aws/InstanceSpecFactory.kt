package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeGroup
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.nodeGroups
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.EBSConfig

/**
 * Specification for creating instances of a particular server type.
 *
 * This data class encapsulates the configuration needed to determine
 * how many instances of a given type need to be created, accounting
 * for any that already exist.
 *
 * @property serverType The type of server (Cassandra, Stress, Control)
 * @property configuredCount Number of instances configured in InitConfig
 * @property existingCount Number of instances that already exist
 * @property instanceType EC2 instance type (e.g., "m5.large")
 * @property ebsConfig Optional EBS configuration for attached storage
 * @property amiId AMI matching this group's own CPU architecture
 * @property missingIndexes the ordinals in `0 until configuredCount` that no existing instance holds
 */
data class InstanceSpec(
    val serverType: ServerType,
    val configuredCount: Int,
    val existingCount: Int,
    val instanceType: String,
    val ebsConfig: EBSConfig?,
    val amiId: String,
    val missingIndexes: List<Int>,
) {
    /** Number of instances to launch to reach the configured count: one per missing ordinal. */
    val neededCount: Int get() = missingIndexes.size
}

/**
 * The ordinals in `0 until configured` that none of [existing] holds, ascending. An instance's
 * ordinal is the number after its server type in its alias (`db3` is 3). A partial launch can
 * leave gaps (`db0`, `db3`, `db6` of 9), so the instances to launch are these, not the ones
 * numbered on from the existing count: that would reuse the aliases of running instances.
 */
fun missingOrdinals(
    serverType: ServerType,
    configured: Int,
    existing: List<DiscoveredInstance>,
): List<Int> {
    val held = existing.mapNotNull { serverType.ordinalOf(it.alias) }.toSet()
    return (0 until configured).filterNot { it in held }
}

/**
 * Factory for creating instance specifications from cluster configuration.
 *
 * This service encapsulates the logic for determining what EC2 instances
 * need to be created based on the InitConfig and existing infrastructure.
 */
interface InstanceSpecFactory {
    /**
     * Creates instance specifications for all server types.
     *
     * @param initConfig The cluster initialization configuration
     * @param existingInstances Map of existing instances by server type
     * @param dbHasInstanceStore Whether the database instance type has local instance store
     * @param amiByArch AMI id resolved for each distinct CPU architecture in the cluster
     * @return List of instance specifications for Cassandra, Stress, and Control nodes
     * @throws IllegalArgumentException if database instance type has no instance store and no EBS configured
     */
    fun createInstanceSpecs(
        initConfig: InitConfig,
        existingInstances: Map<ServerType, List<DiscoveredInstance>>,
        dbHasInstanceStore: Boolean,
        amiByArch: Map<Arch, String>,
    ): List<InstanceSpec>

    /**
     * Creates EBS configuration from init config parameters.
     *
     * @param initConfig The cluster initialization configuration
     * @return EBSConfig if EBS is configured (type != "NONE"), null otherwise
     */
    fun createEbsConfig(initConfig: InitConfig): EBSConfig?
}

/**
 * Default implementation of InstanceSpecFactory.
 */
class DefaultInstanceSpecFactory : InstanceSpecFactory {
    override fun createInstanceSpecs(
        initConfig: InitConfig,
        existingInstances: Map<ServerType, List<DiscoveredInstance>>,
        dbHasInstanceStore: Boolean,
        amiByArch: Map<Arch, String>,
    ): List<InstanceSpec> {
        val ebsConfig = createEbsConfig(initConfig)

        require(dbHasInstanceStore || ebsConfig != null) {
            "Instance type ${initConfig.instanceType} has no local instance store. " +
                "You must specify --ebs.type (e.g., --ebs.type gp3) to attach an EBS volume for data storage."
        }

        return initConfig.nodeGroups().map { group ->
            val existing = existingInstances[group.serverType].orEmpty()
            InstanceSpec(
                serverType = group.serverType,
                configuredCount = group.count,
                existingCount = existing.size,
                instanceType = group.instanceType,
                // Only the database nodes carry the EBS data volume.
                ebsConfig = ebsConfig.takeIf { group.serverType == ServerType.Cassandra },
                amiId = amiFor(group, amiByArch),
                missingIndexes = missingOrdinals(group.serverType, group.count, existing),
            )
        }
    }

    /**
     * The AMI for [group]'s architecture. A group with no configured instances never launches, so
     * it needs no image; only groups that will launch require an AMI resolved for their
     * architecture.
     */
    private fun amiFor(
        group: NodeGroup,
        amiByArch: Map<Arch, String>,
    ): String {
        if (group.count <= 0) return ""
        return amiByArch[group.arch] ?: error("No AMI resolved for architecture ${group.arch}")
    }

    override fun createEbsConfig(initConfig: InitConfig): EBSConfig? =
        if (initConfig.ebsType != "NONE") {
            EBSConfig(
                volumeType = initConfig.ebsType.lowercase(),
                volumeSize = initConfig.ebsSize,
                iops = if (initConfig.ebsIops > 0) initConfig.ebsIops else null,
                throughput = if (initConfig.ebsThroughput > 0) initConfig.ebsThroughput else null,
            )
        } else {
            null
        }
}
