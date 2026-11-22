package com.rustyrazorblade.easydblab.configuration.cassandra

import com.rustyrazorblade.easydblab.Constants

/**
 * Where a Cassandra cluster runs, as far as its cassandra.yaml cares: the only keys that may differ
 * between the VM path and the cassandra-pods kit for the same version (seeds, directories, snitch and
 * native port) come from here, through [CassandraConfigPatch.placement].
 *
 * #1097 later fills in the snitch and DC per pod here, and nowhere else.
 */
sealed interface CassandraPlacement {
    /** Data root that the commit log, data, hints and saved caches directories sit under. */
    val dataRoot: String

    /** A db node on the AMI: seeds by private IP, `/mnt/db1`, and `Ec2Snitch`. */
    data class Vm(
        val seedIps: List<String>,
        val snitch: String = Constants.CassandraConfig.VM_SNITCH,
        override val dataRoot: String = Constants.CassandraConfig.VM_DATA_ROOT,
    ) : CassandraPlacement

    /**
     * A cassandra-pods kit pod: pod 0's stable DNS name as the seed, the claim at
     * `/var/lib/cassandra`, the gossiping snitch (the DC comes from `cassandra-rackdc.properties`),
     * and the NodePort value as the native port, because clients dial the node.
     */
    data class Pods(
        val seedDns: String,
        override val dataRoot: String = Constants.CassandraConfig.POD_DATA_ROOT,
        val nativePort: Int = Constants.Kit.CassandraPods.CQL_NODE_PORT,
    ) : CassandraPlacement
}
