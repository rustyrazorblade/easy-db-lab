package com.rustyrazorblade.easydblab.configuration

/**
 * One server type's node group as [InitConfig] configures it: its instance type, how many
 * instances it has, and its CPU architecture. It is the one mapping from the init fields to a
 * group, read by the capacity plan, the AMI resolution and the instance specs alike, so they can
 * never disagree on which instance type or architecture a group has.
 *
 * [arch] is parsed when read, so a group nobody launches never has its architecture parsed.
 */
data class NodeGroup(
    val serverType: ServerType,
    val instanceType: String,
    val count: Int,
    val archName: String,
) {
    /** The group's CPU architecture. */
    val arch: Arch get() = Arch.valueOf(archName)
}

/** The database, application and control node groups, in that order. */
fun InitConfig.nodeGroups(): List<NodeGroup> =
    listOf(
        NodeGroup(ServerType.Cassandra, instanceType, cassandraInstances, dbArch),
        NodeGroup(ServerType.Stress, stressInstanceType, stressInstances, appArch),
        NodeGroup(ServerType.Control, controlInstanceType, controlInstances, controlArch),
    )
