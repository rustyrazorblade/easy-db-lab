package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
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
 */
data class InstanceSpec(
    val serverType: ServerType,
    val configuredCount: Int,
    val existingCount: Int,
    val instanceType: String,
    val ebsConfig: EBSConfig?,
) {
    /**
     * Number of additional instances needed to reach the configured count.
     * Will be 0 or negative if sufficient instances already exist.
     */
    val neededCount: Int get() = configuredCount - existingCount
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
     * @return List of instance specifications for Cassandra, Stress, and Control nodes
     */
    fun createInstanceSpecs(
        initConfig: InitConfig,
        existingInstances: Map<ServerType, List<DiscoveredInstance>>,
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
    ): List<InstanceSpec> {
        val ebsConfig = createEbsConfig(initConfig)

        val existingCassandraCount = existingInstances[ServerType.Cassandra]?.size ?: 0
        val existingStressCount = existingInstances[ServerType.Stress]?.size ?: 0
        val existingControlCount = existingInstances[ServerType.Control]?.size ?: 0

        return listOf(
            InstanceSpec(
                serverType = ServerType.Cassandra,
                configuredCount = initConfig.cassandraInstances,
                existingCount = existingCassandraCount,
                instanceType = initConfig.instanceType,
                ebsConfig = ebsConfig,
            ),
            InstanceSpec(
                serverType = ServerType.Stress,
                configuredCount = initConfig.stressInstances,
                existingCount = existingStressCount,
                instanceType = initConfig.stressInstanceType,
                ebsConfig = null,
            ),
            InstanceSpec(
                serverType = ServerType.Control,
                configuredCount = initConfig.controlInstances,
                existingCount = existingControlCount,
                instanceType = initConfig.controlInstanceType,
                ebsConfig = null,
            ),
        )
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
