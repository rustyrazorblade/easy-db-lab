package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ServerType

/**
 * The storage rule every node's instance type must meet so that `/mnt/db1` lands on a data disk
 * and not on the 20 GB root volume. Every node writes there: databases and kit PVs on db nodes,
 * the observability backends on the control node, and on every node the K3s data directory and
 * pod logs.
 *
 * A db node may get its data disk from instance store or from the EBS volume `--ebs.type` adds.
 * `--ebs.type` attaches a volume to db nodes only, so a control or app node needs instance store.
 */
object DataDiskRequirement {
    /**
     * Checks that a [serverType] node of [instanceType] has a data disk.
     *
     * @param hasInstanceStore whether [instanceType] has local instance store
     * @param ebsConfigured whether `--ebs.type` is set to something other than `NONE`
     * @throws IllegalArgumentException naming the node type and instance type when it has none
     */
    fun check(
        serverType: ServerType,
        instanceType: String,
        hasInstanceStore: Boolean,
        ebsConfigured: Boolean,
    ) {
        when (serverType) {
            ServerType.Cassandra ->
                require(hasInstanceStore || ebsConfigured) {
                    "Instance type $instanceType has no local instance store. " +
                        "You must specify --ebs.type (e.g., --ebs.type gp3) to attach an EBS volume for data storage."
                }
            ServerType.Stress, ServerType.Control ->
                require(hasInstanceStore) {
                    "The ${serverType.serverType} instance type $instanceType has no local instance store. " +
                        "${serverType.serverType} nodes need an instance type with instance store for their data disk " +
                        "(--ebs.type applies to db nodes only)."
                }
        }
    }
}
